package app.etracker.tracker.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Keeps the last situation picture on disk so the map still shows something
 * (marked as saved data) when the phone is offline or the app restarts, and
 * merges "unchanged" stubs from the server back into the full plan / IPB.
 */
object SituationCache {
    private fun file(context: Context) = File(context.filesDir, "situation.json")

    fun load(context: Context): JSONObject? =
        runCatching { JSONObject(file(context).readText()) }.getOrNull()

    fun save(context: Context, data: JSONObject) {
        runCatching { file(context).writeText(data.toString()) }
    }

    /** When the picture on disk was last refreshed (epoch ms, 0 if none). */
    fun fileTime(context: Context): Long = file(context).takeIf { it.exists() }?.lastModified() ?: 0L

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** The `modified` stamp of a cached part, or null (sent back as a hint to the server). */
    fun modified(data: JSONObject?, part: String): String? =
        data?.optJSONObject(part)?.optString("modified")?.takeIf { it.isNotBlank() }

    /**
     * Replaces `{"unchanged": true}` stubs in [next] with the matching part of
     * [prev]; with nothing to fall back on the part becomes null (the next poll
     * then carries no hint, so the server resends it in full).
     */
    fun merge(prev: JSONObject?, next: JSONObject): JSONObject {
        for (part in listOf("plan", "ipb")) {
            val n = next.optJSONObject(part) ?: continue
            if (!n.optBoolean("unchanged")) continue
            val p = prev?.optJSONObject(part)
            if (p != null && !p.optBoolean("unchanged")) next.put(part, p) else next.put(part, JSONObject.NULL)
        }
        return next
    }
}
