package app.etracker.tracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's own track, kept on the phone for the patrol: while recording is on, every GPS fix the
 * reporting service takes is appended here (screen off, page closed, no network). The Situation page
 * draws it as a trail and can turn it into a route back (EtrackerNative.track*).
 *
 * One line per fix, "epochMs,lat,lon,accuracy", in filesDir (erased by a wipe). Capped at [MAX] fixes:
 * the oldest drop off.
 */
object TrackLog {
    private const val MAX = 20_000
    private const val PREFS = "track_log"
    private fun file(context: Context) = File(context.filesDir, "track.csv")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recording(context: Context): Boolean = prefs(context).getBoolean("on", false)

    fun setRecording(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("on", on).putLong("since", if (on) System.currentTimeMillis() else 0L).apply()
    }

    @Synchronized
    fun add(context: Context, ts: Long, lat: Double, lon: Double, accuracy: Float?) {
        if (!recording(context)) return
        runCatching {
            val f = file(context)
            f.appendText("$ts,${"%.6f".format(java.util.Locale.ROOT, lat)},${"%.6f".format(java.util.Locale.ROOT, lon)},${accuracy?.toInt() ?: ""}\n")
            // trim now and then, not on every fix
            if (f.length() > MAX * 48L) {
                val lines = f.readLines()
                if (lines.size > MAX) f.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** {"on": bool, "since": ms, "points": [[ms, lat, lon, acc|null], …]} for the page. */
    @Synchronized
    fun json(context: Context): String {
        val pts = JSONArray()
        runCatching {
            file(context).takeIf { it.exists() }?.forEachLine { l ->
                val p = l.split(",")
                if (p.size >= 3) {
                    val lat = p[1].toDoubleOrNull()
                    val lon = p[2].toDoubleOrNull()
                    val ts = p[0].toLongOrNull()
                    if (lat != null && lon != null && ts != null) {
                        pts.put(JSONArray().put(ts).put(lat).put(lon).put(p.getOrNull(3)?.toIntOrNull() ?: JSONObject.NULL))
                    }
                }
            }
        }
        return JSONObject().put("on", recording(context)).put("since", prefs(context).getLong("since", 0L)).put("points", pts).toString()
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
