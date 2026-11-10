package app.etracker.tracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** One GPS fix. [ts] is epoch milliseconds (UTC). */
data class Fix(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val heading: Float? = null,
)

/**
 * Client for the eTracker device API (see docs/DEVICE_API.md in the server
 * repo). Every call is a POST; the device authenticates with a one-time
 * enrolment code, then a bearer token. Uses only the platform HTTP stack.
 */
object Api {
    /** [status] is the HTTP status, or 0 for a network failure. */
    class ApiException(val status: Int, message: String) : Exception(message) {
        /** Token unknown or revoked: stop reporting, re-enrol. */
        val authFailure get() = status == 401 || status == 403

        /** Worth retrying later (offline, server busy, rate limited). */
        val retryable get() = status == 0 || status == 429 || status >= 500
    }

    fun normalizeSite(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    fun enrol(siteInput: String, code: String): Enrolment {
        val site = normalizeSite(siteInput)
        val m = call(site, "etracker.devices.enrol_device", JSONObject().put("code", code.trim()))
        return Enrolment(
            token = m.getString("token"),
            device = m.optString("device"),
            label = m.optString("label"),
            unit = m.optString("unit"),
            sidc = m.optString("sidc"),
            intervalSeconds = m.optInt("interval_s", 30),
        )
    }

    /** What a position post tells us: the suggested interval (0 = none) and whether the server wants this device wiped. */
    class Pulse(val intervalSeconds: Int, val wipe: Boolean)

    /** A newer app version offered by the server. */
    class AppUpdate(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val mandatory: Boolean,
        val size: Long,
        val sha256: String,
    )

    /** [update] is null when this build is current; [wipe] rides along on every authenticated reply. */
    class UpdateCheck(val update: AppUpdate?, val wipe: Boolean)

    /** Sends up to 200 fixes (oldest first). */
    fun postPositions(site: String, token: String, fixes: List<Fix>): Pulse {
        val arr = JSONArray()
        fixes.forEach { f ->
            arr.put(
                JSONObject()
                    .put("lat", f.lat)
                    .put("lon", f.lon)
                    .put("ts", Instant.ofEpochMilli(f.ts).toString()) // ISO-8601 with 'Z'
                    .apply {
                        f.accuracy?.let { put("accuracy", it.toDouble()) }
                        f.speed?.let { put("speed", it.toDouble()) }
                        f.heading?.let { put("heading", it.toDouble()) }
                    },
            )
        }
        val m = call(site, "etracker.devices.post_positions", JSONObject().put("token", token).put("positions", arr).put("app_version", app.etracker.tracker.BuildConfig.VERSION_CODE))
        return Pulse(m.optInt("interval_s", 0), m.optBoolean("wipe", false))
    }

    /** Asks whether a newer app exists than [versionCode]. Also works as a heartbeat (carries the wipe flag). */
    fun getAppUpdate(site: String, token: String, versionCode: Int): UpdateCheck {
        val m = call(site, "etracker.devices.get_app_update", JSONObject().put("token", token).put("version_code", versionCode))
        val wipe = m.optBoolean("wipe", false)
        if (!m.optBoolean("update_available", false)) return UpdateCheck(null, wipe)
        return UpdateCheck(
            AppUpdate(
                versionCode = m.getInt("version_code"),
                versionName = m.optString("version_name"),
                notes = m.optString("notes"),
                mandatory = m.optBoolean("mandatory", false),
                size = m.optLong("size", 0),
                sha256 = m.optString("sha256").lowercase(),
            ),
            wipe,
        )
    }

    /** Tells the server this device has erased itself; the server then revokes the token. */
    fun ackWipe(site: String, token: String) {
        call(site, "etracker.devices.ack_wipe", JSONObject().put("token", token))
    }

    /**
     * Streams the APK to [dest] (POST, so the token stays out of URLs and logs) and
     * returns its SHA-256 (lower-case hex). [onProgress] gets (bytes so far, total or 0).
     */
    fun downloadApp(site: String, token: String, dest: File, expectedSize: Long, onProgress: (Long, Long) -> Unit): String {
        val conn = try {
            URL("$site/api/method/etracker.devices.download_app").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "Bad server address")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw ApiException(status, serverMessage(text) ?: "HTTP $status")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) { lastReport = now; onProgress(done, total) }
                    }
                }
            }
            onProgress(done, total)
            return md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The read-only situation picture for this device's operation (plan, live
     * units, watch log, optionally threat). Pass back the `modified` values of
     * the plan / IPB you already hold: an unchanged part comes back as a stub
     * (`{"unchanged": true}`) so frequent polling stays cheap.
     */
    fun getSituation(site: String, token: String, planModified: String?, ipbModified: String?): JSONObject {
        val body = JSONObject().put("token", token)
        planModified?.let { body.put("plan_modified", it) }
        ipbModified?.let { body.put("ipb_modified", it) }
        return call(site, "etracker.devices.get_situation", body)
    }

    /**
     * A field report from the Situation page (task status or a target observation),
     * posted as JSON. The server answers with the created Watch entry / updated task.
     * Returns the server's `message` as JSON text.
     */
    fun postReport(site: String, token: String, reportJson: String): String {
        val body = JSONObject().put("token", token).put("report", reportJson)
        return call(site, "etracker.devices.post_report", body).toString()
    }

    private fun call(site: String, method: String, body: JSONObject): JSONObject {
        val (status, text) = post("$site/api/method/$method", body)
        if (status !in 200..299) throw ApiException(status, serverMessage(text) ?: "HTTP $status")
        return try {
            JSONObject(text).getJSONObject("message")
        } catch (e: Exception) {
            throw ApiException(status, "Unexpected response from server")
        }
    }

    private fun post(url: String, body: JSONObject): Pair<Int, String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "Bad server address")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    /** Frappe puts human messages in `_server_messages` (a JSON list of JSON strings). */
    private fun serverMessage(text: String): String? = try {
        val o = JSONObject(text)
        val raw = o.optString("_server_messages")
        val fromList = if (raw.isNotBlank()) {
            val a = JSONArray(raw)
            (0 until a.length()).joinToString("; ") { JSONObject(a.getString(it)).optString("message") }
        } else ""
        (fromList.ifBlank { o.optString("exception") }).replace(Regex("<[^>]+>"), "").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    fun describe(e: Throwable): String = when (e) {
        is ApiException -> when {
            e.status == 0 -> "Can't reach the server (${e.message})"
            e.authFailure -> e.message ?: "Not authorised"
            else -> e.message ?: "Server error"
        }
        else -> e.message ?: "Something went wrong"
    }
}
