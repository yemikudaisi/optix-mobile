package app.etracker.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One offline map pack as the server describes it (get_situation -> mapPacks). */
data class PackInfo(
    val id: String,
    val title: String,
    /** west, south, east, north */
    val bounds: List<Double>,
    val minZoom: Int,
    val maxZoom: Int,
    val format: String,
    val size: Long,
    val sha256: String,
    val modified: String,
    val raw: JSONObject,
) {
    companion object {
        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

        fun from(o: JSONObject): PackInfo? {
            val id = o.optString("id")
            if (!ID.matches(id)) return null
            val b = o.optJSONArray("bounds") ?: return null
            if (b.length() != 4) return null
            return PackInfo(
                id = id,
                title = o.optString("title").ifBlank { id },
                bounds = (0 until 4).map { b.optDouble(it) },
                minZoom = o.optInt("minZoom", 0),
                maxZoom = o.optInt("maxZoom", 0),
                format = o.optString("format", "png"),
                size = o.optLong("size", 0),
                sha256 = o.optString("sha256").lowercase(),
                modified = o.optString("modified"),
                raw = o,
            )
        }
    }
}

/** A row of the Status tab's "Offline maps" list. */
data class PackRow(
    val info: PackInfo,
    /** The server offers it to this phone (false: only on the phone, offer not yet confirmed). */
    val offered: Boolean,
    val installed: Boolean,
    /** Installed, but not the file the server now offers (checksum differs). */
    val needsUpdate: Boolean,
    /** Bytes so far / total while downloading, else null. */
    val progress: Pair<Long, Long>? = null,
    val error: String? = null,
)

/**
 * Offline map packs: raster MBTiles files the server publishes for this phone's operation.
 *
 * * The list comes with every situation poll (`mapPacks`); packs the server no longer offers
 *   (withdrawn, superseded, another operation) are deleted from the phone.
 * * Downloads go over HTTPS with the device token (POST, token in the body), resume with an
 *   HTTP Range after an interruption, and are kept only when the SHA-256 matches.
 * * Automatic download is off by default; with "Download on Wi-Fi automatically" on, offered
 *   packs are fetched while the phone is on an unmetered network.
 * * Tiles are served to the Situation page from the files (see [serve]); the page asks for
 *   `https://tiles.optix.invalid/<pack>/<z>/<x>/<y>` and the WebView client answers.
 *
 * Files live in filesDir/mappacks (`<id>.mbtiles` + `<id>.pack.json`), so a remote wipe erases them.
 */
object MapPacks {
    const val TILE_HOST = "tiles.optix.invalid"
    private const val PREFS = "map_packs"
    private const val KEY_AUTO = "auto_wifi"
    private const val OFFERED = "offered.json"

    private val _rows = MutableStateFlow<List<PackRow>>(emptyList())
    val rows: StateFlow<List<PackRow>> = _rows.asStateFlow()
    private val _autoWifi = MutableStateFlow(false)
    val autoWifi: StateFlow<Boolean> = _autoWifi.asStateFlow()
    /** True once the server's list has been seen (on this run or a saved one). */
    private val _known = MutableStateFlow(false)
    val known: StateFlow<Boolean> = _known.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val errors = ConcurrentHashMap<String, String>()
    private val dbs = ConcurrentHashMap<String, SQLiteDatabase>()
    @Volatile private var offered: List<PackInfo>? = null
    @Volatile private var loaded = false

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "mappacks").apply { mkdirs() }
    private fun mbtiles(ctx: Context, id: String) = File(dir(ctx), "$id.mbtiles")
    private fun meta(ctx: Context, id: String) = File(dir(ctx), "$id.pack.json")
    private fun part(ctx: Context, id: String) = File(dir(ctx), "$id.part")

    /** Reads the saved offer list and the auto-download switch (once per process). */
    fun load(context: Context) {
        if (loaded) return
        val ctx = context.applicationContext
        loaded = true
        _autoWifi.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)
        offered = runCatching { parse(JSONArray(File(dir(ctx), OFFERED).readText())) }.getOrNull()
        _known.value = offered != null
        publish(ctx)
    }

    /** Packs downloaded and verified on this phone. */
    fun installed(context: Context): List<PackInfo> {
        val ctx = context.applicationContext
        return dir(ctx).listFiles { f -> f.name.endsWith(".pack.json") }.orEmpty().mapNotNull { f ->
            val p = runCatching { PackInfo.from(JSONObject(f.readText())) }.getOrNull()
            p?.takeIf { mbtiles(ctx, it.id).isFile }
        }.sortedBy { it.title.lowercase() }
    }

    /** For get_situation's `packs`: "MAP-00001:<sha256>,…" ("" = none). */
    fun heldParam(context: Context): String = installed(context).joinToString(",") { "${it.id}:${it.sha256}" }

    /** What the Situation page draws: the installed packs (Situation.setOfflinePacks). */
    fun pageJson(context: Context): String {
        val a = JSONArray()
        installed(context).forEach { p ->
            a.put(
                JSONObject().put("id", p.id).put("title", p.title).put("bounds", JSONArray(p.bounds))
                    .put("minZoom", p.minZoom).put("maxZoom", p.maxZoom).put("format", p.format),
            )
        }
        return a.toString()
    }

    private fun parse(a: JSONArray): List<PackInfo> = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PackInfo::from) }

    /**
     * The server's list (get_situation -> mapPacks). [present] false (an older server, or a
     * reply without the key) changes nothing. Packs no longer offered are deleted.
     */
    fun onServerList(context: Context, list: JSONArray?, present: Boolean) {
        if (!present) return
        val ctx = context.applicationContext
        load(ctx)
        val next = parse(list ?: JSONArray())
        offered = next
        _known.value = true
        runCatching { File(dir(ctx), OFFERED).writeText(JSONArray(next.map { it.raw }).toString()) }
        val keep = next.map { it.id }.toSet()
        installed(ctx).filter { it.id !in keep }.forEach { remove(ctx, it.id) }
        jobs.keys.filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
        publish(ctx)
        if (_autoWifi.value && onUnmetered(ctx)) autoDownload(ctx)
    }

    private fun autoDownload(ctx: Context) {
        val have = installed(ctx).associateBy { it.id }
        offered.orEmpty().filter { have[it.id]?.sha256 != it.sha256 && errors[it.id] == null }.forEach { download(ctx, it.id) }
    }

    fun setAutoWifi(context: Context, on: Boolean) {
        val ctx = context.applicationContext
        _autoWifi.value = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
        if (on && onUnmetered(ctx)) {
            errors.clear()
            autoDownload(ctx)
        }
    }

    fun onUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Asks the server for the current list now (Status tab "Refresh"). */
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        if (_refreshing.value) return
        _refreshing.value = true
        _note.value = null
        scope.launch {
            try {
                val s = Session(ctx)
                val site = s.site ?: return@launch
                val token = s.token ?: return@launch
                val cache = SituationCache.load(ctx)
                val r = Api.getSituation(site, token, SituationCache.modified(cache, "plan"), SituationCache.modified(cache, "ipb"), heldParam(ctx))
                if (r.optBoolean("wipe", false)) {
                    DeviceReset.wipe(ctx)
                    return@launch
                }
                onServerList(ctx, r.optJSONArray("mapPacks"), r.has("mapPacks"))
                if (!r.has("mapPacks")) _note.value = "The server does not offer offline maps yet."
            } catch (e: Api.ApiException) {
                if (e.authFailure) DeviceReset.signOut(ctx) else _note.value = Api.describe(e)
            } catch (e: Exception) {
                _note.value = e.message ?: "Could not refresh"
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Download (or update) one offered pack. Resumes a partial file; keeps it only if the checksum matches. */
    fun download(context: Context, id: String) {
        val ctx = context.applicationContext
        val info = offered?.firstOrNull { it.id == id } ?: return
        if (jobs[id]?.isActive == true) return
        errors.remove(id)
        jobs[id] = scope.launch {
            val job = coroutineContext[Job]
            try {
                val s = Session(ctx)
                val site = s.site ?: throw IllegalStateException("This device is not enrolled.")
                val token = s.token ?: throw IllegalStateException("This device is not enrolled.")
                val tmp = part(ctx, id)
                val need = info.size - (if (tmp.isFile) tmp.length() else 0L)
                if (dir(ctx).usableSpace < need + 50L * 1024 * 1024) {
                    throw IllegalStateException("Not enough free storage (needs %.0f MB).".format(need / 1048576.0))
                }
                progress[id] = (if (tmp.isFile) tmp.length() else 0L) to info.size
                publish(ctx)
                Api.downloadMapPack(site, token, id, tmp) { done, total ->
                    if (job?.isActive == false) throw kotlinx.coroutines.CancellationException("cancelled")
                    progress[id] = done to (if (total > 0) total else info.size)
                    publish(ctx)
                }
                val sha = sha256(tmp)
                if (!sha.equals(info.sha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException("The download failed verification (checksum mismatch) and was discarded. Try again.")
                }
                dbs.remove(id)?.close()
                val dest = mbtiles(ctx, id)
                dest.delete()
                if (!tmp.renameTo(dest)) throw IllegalStateException("Could not store the map.")
                meta(ctx, id).writeText(info.raw.toString())
            } catch (e: Api.ApiException) {
                if (e.authFailure) DeviceReset.signOut(ctx)
                errors[id] = if (e.status == 0) "No connection: tap Download to resume." else Api.describe(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelled by the user or because the pack was withdrawn: the partial file stays for a resume
            } catch (e: Exception) {
                errors[id] = e.message ?: "Download failed"
            } finally {
                progress.remove(id)
                jobs.remove(id)
                publish(ctx)
            }
        }
    }

    fun cancel(context: Context, id: String) {
        jobs.remove(id)?.cancel()
        publish(context.applicationContext)
    }

    /** Delete a pack from the phone (the server still offers it; it can be downloaded again). */
    fun delete(context: Context, id: String) {
        val ctx = context.applicationContext
        jobs.remove(id)?.cancel()
        remove(ctx, id)
        publish(ctx)
    }

    private fun remove(ctx: Context, id: String) {
        dbs.remove(id)?.close()
        mbtiles(ctx, id).delete()
        meta(ctx, id).delete()
        part(ctx, id).delete()
        errors.remove(id)
    }

    /** Sign-out / revoke / wipe: close and delete everything. */
    fun clear(context: Context) {
        val ctx = context.applicationContext
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        dbs.values.forEach { runCatching { it.close() } }
        dbs.clear()
        progress.clear()
        errors.clear()
        offered = null
        _known.value = false
        runCatching { dir(ctx).deleteRecursively() }
        _rows.value = emptyList()
    }

    private fun publish(ctx: Context) {
        val inst = installed(ctx).associateBy { it.id }
        val off = offered
        val rows = mutableListOf<PackRow>()
        off?.forEach { p ->
            val have = inst[p.id]
            rows += PackRow(p, offered = true, installed = have != null, needsUpdate = have != null && have.sha256 != p.sha256, progress = progress[p.id], error = errors[p.id])
        }
        inst.values.filter { i -> off == null || off.none { it.id == i.id } }.forEach { i ->
            rows += PackRow(i, offered = false, installed = true, needsUpdate = false)
        }
        _rows.value = rows
    }

    // ------------------------------------------------------------------ tiles for the WebView

    private val TRANSPARENT_PNG: ByteArray = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        android.util.Base64.DEFAULT,
    )

    private fun open(ctx: Context, id: String): SQLiteDatabase? {
        dbs[id]?.let { if (it.isOpen) return it }
        val f = mbtiles(ctx, id)
        if (!f.isFile) return null
        return synchronized(dbs) {
            dbs[id]?.takeIf { it.isOpen } ?: runCatching {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            }.getOrNull()?.also { dbs[id] = it }
        }
    }

    /** One tile (XYZ y; MBTiles rows are TMS: y_tms = 2^z - 1 - y), or null. */
    fun tile(context: Context, id: String, z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 0..24 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val db = open(context.applicationContext, id) ?: return null
        val tms = (1 shl z) - 1 - y
        return runCatching {
            db.rawQuery(
                "select tile_data from tiles where zoom_level = ? and tile_column = ? and tile_row = ? limit 1",
                arrayOf(z.toString(), x.toString(), tms.toString()),
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        }.getOrNull()
    }

    private fun mime(b: ByteArray): String = when {
        b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "application/octet-stream"
    }

    /**
     * Answer for https://tiles.optix.invalid/<pack>/<z>/<x>/<y>[.ext] from the WebView. A tile the
     * pack does not have (or a pack not on the phone) is a transparent PNG, so the page never shows
     * a broken image and the map under it shows through.
     */
    fun serve(context: Context, url: Uri): WebResourceResponse {
        val seg = url.pathSegments
        val bytes = if (seg.size == 4) {
            val id = seg[0]
            val z = seg[1].toIntOrNull()
            val x = seg[2].toIntOrNull()
            val y = seg[3].substringBefore('.').toIntOrNull()
            if (z != null && x != null && y != null && Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(id)) tile(context, id, z, x, y) else null
        } else null
        val body = bytes ?: TRANSPARENT_PNG
        val headers = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
        return WebResourceResponse(if (bytes != null) mime(bytes) else "image/png", null, 200, "OK", headers, ByteArrayInputStream(body))
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
