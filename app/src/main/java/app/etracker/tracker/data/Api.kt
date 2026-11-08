package app.etracker.tracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    /** Sends up to 200 fixes (oldest first). Returns the server's suggested interval. */
    fun postPositions(site: String, token: String, fixes: List<Fix>): Int {
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
        val m = call(site, "etracker.devices.post_positions", JSONObject().put("token", token).put("positions", arr))
        return m.optInt("interval_s", 0)
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
