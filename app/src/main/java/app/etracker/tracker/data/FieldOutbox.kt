package app.etracker.tracker.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import app.etracker.tracker.service.FieldNotifier
import app.etracker.tracker.service.TrackingService
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume

/**
 * Durable priority outbox for field reports that must not be lost: SOS, SOS cancel and check-in
 * (the Situation page may hand it anything else too, through `EtrackerNative.enqueue`).
 *
 * Items are written to a small JSON file in filesDir before anything is sent, so closing the app,
 * a crash or a reboot never loses an SOS. Delivery order is SOS / SOS cancel first, then check-in,
 * then the rest, oldest first within each. A worker on an app-process scope (it outlives the UI and
 * the tracking service) sends them; on no signal it retries every 5 s backing off to 30 s, and a
 * network becoming available kicks it at once. A retryable error never drops an item; a refusal
 * (417, 403 and other 4xx) drops it and reports the server's message; a dead token (401) signs out.
 * `client_id` makes a resend after a lost reply harmless on the server.
 *
 * Every final outcome (and the first failed attempt of each item, with retry = true) goes out on
 * [results] for the Status tab and the Situation page.
 */
object FieldOutbox {
    private const val FILE = "field_outbox.json"

    data class Item(val id: String, val kind: String, val json: String, val at: Long)

    /** An outcome of sending [id]. [json] is the server's reply ("null" when there is none). */
    data class Result(val id: String, val kind: String, val ok: Boolean, val retry: Boolean, val msg: String, val json: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // like DeviceReset's: outlives the UI and the service
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val sendLock = ReentrantLock()
    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 64)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    private var items: MutableList<Item>? = null // guarded by `this`
    private val tried = HashSet<String>() // ids that already reported a failed attempt
    @Volatile private var started = false

    // ------------------------------------------------------------ storage

    @Synchronized
    private fun list(ctx: Context): MutableList<Item> {
        items?.let { return it }
        val out = ArrayList<Item>()
        runCatching {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (f.isFile) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out += Item(o.getString("id"), o.optString("kind"), o.getString("json"), o.optLong("at"))
                }
            }
        }
        items = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context) {
        val a = JSONArray()
        list(ctx).forEach { a.put(JSONObject().put("id", it.id).put("kind", it.kind).put("json", it.json).put("at", it.at)) }
        runCatching {
            val dir = ctx.applicationContext.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(File(dir, FILE))) { File(dir, FILE).writeText(a.toString()); tmp.delete() }
        }
    }

    private fun rank(kind: String) = when (kind) {
        "sos", "sos_cancel" -> 0
        "checkin" -> 1
        else -> 2
    }

    @Synchronized
    private fun next(ctx: Context): Item? =
        list(ctx).sortedWith(compareBy<Item>({ rank(it.kind) }, { it.at })).firstOrNull()

    @Synchronized
    private fun remove(ctx: Context, id: String) {
        list(ctx).removeAll { it.id == id }
        tried.remove(id)
        save(ctx)
        publish(ctx)
    }

    @Synchronized
    private fun publish(ctx: Context) {
        val q = list(ctx).sortedWith(compareBy<Item>({ rank(it.kind) }, { it.at })).map { QueuedItem(it.id, it.kind, it.at) }
        FieldState.update { copy(queued = q) }
    }

    /** What is waiting (priority order). */
    @Synchronized
    fun pending(ctx: Context): List<Item> = list(ctx).sortedWith(compareBy<Item>({ rank(it.kind) }, { it.at }))

    // ------------------------------------------------------------ public

    /**
     * Load what survived the last run and start the worker (idempotent). Called at app start and by
     * the tracking service, so an SOS queued before a reboot goes as soon as there is signal.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        publish(app)
        if (value(app).any { it.kind == "sos" }) {
            // an SOS still waiting: report fast again until the server takes over
            FieldState.update { if (localBurstUntilMs > System.currentTimeMillis()) this else copy(localBurstUntilMs = System.currentTimeMillis() + FieldUi.BURST_MS) }
        }
        if (started) { kick(); return }
        synchronized(this) {
            if (started) return
            started = true
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = kick()
            })
        }
        scope.launch { worker(app) }
        kick()
    }

    @Synchronized
    private fun value(ctx: Context) = list(ctx).toList()

    /**
     * Put a report in the outbox ([reportJson] is the post_report `report` object as JSON text) and
     * send it as soon as possible. [id] is the caller's handle (the Situation page's report id); the
     * report's own `client_id` (added when missing) is what makes a resend harmless on the server.
     */
    fun enqueue(ctx: Context, id: String, reportJson: String) {
        val app = ctx.applicationContext
        if (DeviceReset.active) return
        val o = runCatching { JSONObject(reportJson) }.getOrNull() ?: return
        if (!o.has("client_id") || o.optString("client_id").isBlank()) o.put("client_id", id)
        val kind = o.optString("kind")
        synchronized(this) {
            val l = list(app)
            if (l.any { it.id == id }) return // already queued (a page retry)
            l += Item(id, kind, o.toString(), System.currentTimeMillis())
            save(app)
        }
        if (kind == "sos") {
            FieldState.update { copy(localBurstUntilMs = System.currentTimeMillis() + FieldUi.BURST_MS) }
            // report fast from now on: the service must be running (it needs the location permission)
            TrackingService.startIfPermitted(app)
        }
        publish(app)
        start(app)
        kick()
    }

    /** Wake the worker now (new item, network back). */
    fun kick() {
        signal.trySend(Unit)
    }

    /**
     * Sends what is waiting, unless the worker is already at it. Blocking: the tracking service's
     * flusher calls this before its fixes so an SOS never waits behind a position backlog.
     */
    fun sendIfIdle(ctx: Context) {
        if (!sendLock.tryLock()) return
        try { sendAll(ctx.applicationContext) } finally { sendLock.unlock() }
    }

    /** Sign-out / wipe: forget everything queued and the SOS / check-in picture. */
    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            items = ArrayList()
            tried.clear()
            runCatching { File(app.filesDir, FILE).delete() }
            runCatching { File(app.filesDir, "$FILE.tmp").delete() }
        }
        FieldState.reset()
        FieldNotifier.clear(app)
    }

    // ------------------------------------------------------------ delivery

    private enum class Outcome { EMPTY, RETRY }

    private suspend fun worker(app: Context) {
        var failures = 0
        while (true) {
            val r = sendLock.withLock { sendAll(app) }
            if (r == Outcome.EMPTY) {
                failures = 0
                signal.receive()
            } else {
                failures++
                // "the moment there is any signal": 5 s, 10 s, 20 s, then every 30 s; a network coming up cuts it short
                val wait = minOf(30_000L, 5_000L shl minOf(failures - 1, 3))
                withTimeoutOrNull(wait) { signal.receive() }
            }
        }
    }

    private fun sendAll(app: Context): Outcome {
        while (true) {
            if (DeviceReset.active) return Outcome.EMPTY
            val item = next(app) ?: return Outcome.EMPTY
            val session = Session(app)
            val site = session.site
            val token = session.token
            if (site == null || token == null) return Outcome.EMPTY // not enrolled (sign-out clears the outbox anyway)
            try {
                val text = Api.postReport(site, token, item.json)
                val reply = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                remove(app, item.id)
                FieldState.applyReport(app, item.kind, reply)
                _results.tryEmit(Result(item.id, item.kind, true, false, "", text))
                // the server piggy-backs the wipe order on its reply
                if (reply.optBoolean("wipe", false)) { DeviceReset.wipe(app); return Outcome.EMPTY }
            } catch (e: Api.ApiException) {
                when {
                    e.retryable -> {
                        if (synchronized(this) { tried.add(item.id) }) {
                            _results.tryEmit(Result(item.id, item.kind, false, true, Api.describe(e), "null"))
                        }
                        return Outcome.RETRY
                    }
                    // only an unknown/revoked token (401) ends the enrolment; a 403 is "not allowed to do that"
                    e.status == 401 -> {
                        DeviceReset.signOut(app)
                        return Outcome.EMPTY
                    }
                    else -> {
                        // refused (417 validation / command refusal, 403): drop it so it cannot block the queue
                        remove(app, item.id)
                        _results.tryEmit(Result(item.id, item.kind, false, false, Api.describe(e), "null"))
                    }
                }
            } catch (e: Exception) {
                // malformed reply or the like: keep it and try again later
                return Outcome.RETRY
            }
        }
    }

    // ------------------------------------------------------------ helpers for the Status tab

    /** Raise an SOS now ([category] Contact | Casualty | Vehicle | Lost | Other). Returns its outbox id. */
    suspend fun raiseSos(ctx: Context, category: String, note: String): String {
        val id = UUID.randomUUID().toString()
        val o = JSONObject().put("client_id", id).put("kind", "sos").put("category", category).put("note", note.trim())
        position(ctx)?.let { (lat, lng, acc) ->
            o.put("lat", lat).put("lng", lng)
            acc?.let { o.put("accuracy", it.toDouble()) }
        }
        enqueue(ctx, id, o.toString())
        return id
    }

    /** Call off this phone's SOS (false alarm). [alert] null: the server takes this phone's open one. */
    fun cancelSos(ctx: Context, alert: String?, reason: String): String {
        val id = UUID.randomUUID().toString()
        val o = JSONObject().put("client_id", id).put("kind", "sos_cancel").put("reason", reason.trim())
        alert?.let { o.put("alert", it) }
        enqueue(ctx, id, o.toString())
        return id
    }

    /** CHECK IN — ALL OK, optionally with a unit [status]. */
    fun checkIn(ctx: Context, status: String?): String {
        val id = UUID.randomUUID().toString()
        val o = JSONObject().put("client_id", id).put("kind", "checkin")
        status?.let { o.put("status", it) }
        val s = TrackerState.flow.value
        if (s.lat != null && s.lon != null) o.put("lat", s.lat).put("lng", s.lon)
        enqueue(ctx, id, o.toString())
        return id
    }

    /**
     * The best position at hand: the tracking service's last fix, else the fused provider's last
     * known location (if location is permitted; waits at most 2 s), else none.
     */
    @SuppressLint("MissingPermission") // checked below
    private suspend fun position(ctx: Context): Triple<Double, Double, Float?>? {
        val s = TrackerState.flow.value
        if (s.lat != null && s.lon != null) return Triple(s.lat, s.lon, s.accuracy)
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    LocationServices.getFusedLocationProviderClient(ctx.applicationContext).lastLocation
                        .addOnSuccessListener { loc ->
                            if (cont.isActive) cont.resume(loc?.let { Triple(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) })
                        }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}
