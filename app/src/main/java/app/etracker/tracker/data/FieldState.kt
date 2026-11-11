package app.etracker.tracker.data

import android.content.Context
import app.etracker.tracker.service.FieldNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** This phone's own SOS as the server last described it (open, or closed within the last 30 min). */
data class OwnSos(
    val id: String,
    val status: String,
    val open: Boolean,
    val category: String,
    val note: String,
    val dtg: String,
    val raisedAt: String,
    val unitLabel: String,
    val ackBy: String?,
    val ackAt: String?,
    val ackCount: Int,
    val resolvedBy: String?,
    val resolvedAt: String?,
    val resolvedNote: String?,
    val cancelledBy: String?,
    val cancelledAt: String?,
    val cancelReason: String?,
    /** The SOS reporting interval while the server's fast-reporting burst runs, else null. */
    val intervalS: Int?,
    /** The server's JSON, handed to the Situation page unchanged. */
    val raw: String,
)

/** An open SOS of this unit or a unit below it, raised by another phone. */
data class SosAlertItem(
    val id: String,
    val status: String,
    val category: String,
    val unitLabel: String,
    val dtg: String,
    val raisedBy: String,
    val canAct: Boolean,
)

/** The check-in picture of this phone's unit. [state] is ok | due | overdue | off. */
data class CheckinInfo(
    val active: Boolean,
    val intervalMin: Int?,
    val graceMin: Int,
    val lastAt: String?,
    val lastBy: String?,
    val lastStatus: String?,
    val dueAt: String?,
    val overdueAt: String?,
    /** Seconds until due when [receivedMs] (this phone's clock) was taken; server-relative, so no clock skew. */
    val dueInS: Long?,
    val state: String,
    val receivedMs: Long,
    val raw: String,
) {
    /** Seconds until due now, counting down locally from the server's figure. */
    fun dueInNow(nowMs: Long = System.currentTimeMillis()): Long? = dueInS?.let { it - (nowMs - receivedMs) / 1000 }
}

/** One item waiting in [FieldOutbox], as shown to the UI and the Situation page. */
data class QueuedItem(val id: String, val kind: String, val at: Long)

data class FieldUi(
    val sos: OwnSos? = null,
    val sosAlerts: List<SosAlertItem> = emptyList(),
    val checkin: CheckinInfo? = null,
    /** What is waiting in the outbox (SOS first). */
    val queued: List<QueuedItem> = emptyList(),
    /**
     * Set (this phone's clock) the moment an SOS is enqueued: until then the service reports at the
     * SOS interval even before the server has heard of the alert (no signal yet). Cleared when a
     * cancel is accepted or the server reports the SOS Resolved / Cancelled.
     */
    val localBurstUntilMs: Long = 0,
    /** DTG of the last check-in this phone sent (from the server's reply). */
    val lastCheckinDtg: String? = null,
) {
    val sosQueued: Boolean get() = queued.any { it.kind == "sos" }
    val cancelQueued: Boolean get() = queued.any { it.kind == "sos_cancel" }
    val checkinQueued: Boolean get() = queued.any { it.kind == "checkin" }

    /** Own SOS raised and not closed (or still waiting to go out). */
    val sosActive: Boolean get() = sosQueued || sos?.open == true

    /** Report at the SOS interval: the local burst after raising, or the server's burst. */
    fun burst(nowMs: Long = System.currentTimeMillis()): Boolean =
        localBurstUntilMs > nowMs || (sos?.open == true && sos.intervalS != null)

    /** The interval to report at during [burst]. */
    val burstIntervalS: Int get() = sos?.intervalS?.takeIf { it in 1..60 } ?: SOS_INTERVAL_S

    /** The JSON pushed to the Situation page (Situation.setNativeState). */
    fun pageJson(): String {
        val q = JSONArray()
        queued.forEach { q.put(JSONObject().put("id", it.id).put("kind", it.kind).put("at", it.at)) }
        return JSONObject()
            .put("queued", q)
            .put("sos", sos?.let { JSONObject(it.raw) } ?: JSONObject.NULL)
            .put("checkin", checkin?.let { JSONObject(it.raw) } ?: JSONObject.NULL)
            .put("localBurstUntil", localBurstUntilMs)
            .toString()
    }

    companion object {
        /** Used locally before the server has answered (the server default, sos_interval_s). */
        const val SOS_INTERVAL_S = 5
        /** How long the local burst runs (the server default, sos_burst_min). */
        const val BURST_MS = 15 * 60 * 1000L
    }
}

/**
 * SOS and check-in state shared by the tracking service, the Status tab and the Situation page.
 *
 * Fed from every server contact that carries it: post_positions and get_field_state replies (the
 * service), the get_situation poll (the Situation tab) and the outbox's post_report replies. Those
 * arrive out of order, so every reader takes a [generation] before its request and passes it back:
 * a reply to a request that started before the outbox last changed the picture (an SOS accepted,
 * a cancel accepted, a check-in) is ignored for the SOS / check-in parts, so a stale poll cannot
 * flip a just-raised SOS back to the previous one.
 */
object FieldState {
    private val _flow = MutableStateFlow(FieldUi())
    val flow: StateFlow<FieldUi> = _flow.asStateFlow()
    val value: FieldUi get() = _flow.value

    private val gen = AtomicLong(0)

    /** Take before a server request; pass to [applyServer] with its reply. */
    fun generation(): Long = gen.get()

    fun update(block: FieldUi.() -> FieldUi) = _flow.update(block)

    /** Forget everything (sign-out, wipe). */
    fun reset() {
        gen.incrementAndGet()
        _flow.value = FieldUi()
    }

    /**
     * A reply carrying the field state: post_positions / get_field_state (`interval_s`, `sos`,
     * `sosAlerts`, `checkin`) or get_situation (the same, `intervalS`). Missing keys leave that part
     * as it was. [alerts] false: the reply's `sosAlerts` is not meaningful (a Situation reply for a
     * device with no situation access always sends an empty list).
     */
    fun applyServer(ctx: Context, m: JSONObject, generation: Long, alerts: Boolean = true) {
        val fresh = generation == gen.get()
        _flow.update { cur ->
            var next = cur
            if (fresh && m.has("sos")) {
                val sos = parseSos(m.optJSONObject("sos"))
                next = next.copy(sos = sos)
                // the server says it is over (and nothing newer is waiting to go): stop the local burst
                if (sos != null && !sos.open && !next.sosQueued) next = next.copy(localBurstUntilMs = 0)
            }
            if (fresh && m.has("checkin")) next = next.copy(checkin = parseCheckin(m.optJSONObject("checkin")))
            if (alerts && m.has("sosAlerts")) next = next.copy(sosAlerts = parseAlerts(m.optJSONArray("sosAlerts")))
            next
        }
        FieldNotifier.onFieldState(ctx, _flow.value)
    }

    /** The outbox delivered [kind]; [reply] is post_report's message. */
    fun applyReport(ctx: Context, kind: String, reply: JSONObject) {
        gen.incrementAndGet()
        _flow.update { cur ->
            when (kind) {
                "sos" -> reply.optJSONObject("alert")?.let { a ->
                    // the reply's interval_s is the SOS interval while the burst runs
                    val iv = reply.optInt("interval_s", 0).takeIf { a.optBoolean("open", false) && it in 1..60 }
                    if (!a.has("intervalS")) a.put("intervalS", iv ?: JSONObject.NULL)
                    cur.copy(sos = parseSos(a))
                } ?: cur
                "sos_cancel" -> {
                    val a = reply.optJSONObject("alert")
                    a?.put("intervalS", JSONObject.NULL)
                    cur.copy(sos = parseSos(a) ?: cur.sos, localBurstUntilMs = if (cur.sosQueued) cur.localBurstUntilMs else 0)
                }
                "checkin" -> cur.copy(
                    checkin = if (reply.has("checkin")) parseCheckin(reply.optJSONObject("checkin")) else cur.checkin,
                    lastCheckinDtg = reply.optString("dtg").takeIf { it.isNotBlank() && it != "null" } ?: cur.lastCheckinDtg,
                )
                else -> cur
            }
        }
        FieldNotifier.onFieldState(ctx, _flow.value)
    }

    // ---- parsing (org.json gives "null" strings for JSON null: str() maps those to null)

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    private fun parseSos(o: JSONObject?): OwnSos? {
        if (o == null) return null
        val id = o.str("id") ?: return null
        val ack = o.optJSONObject("ack")
        val res = o.optJSONObject("resolved")
        val can = o.optJSONObject("cancelled")
        val status = o.str("status") ?: "Active"
        return OwnSos(
            id = id,
            status = status,
            open = if (o.has("open")) o.optBoolean("open") else status == "Active" || status == "Acknowledged",
            category = o.str("category") ?: "Other",
            note = o.str("note") ?: "",
            dtg = o.str("dtg") ?: "",
            raisedAt = o.str("raisedAt") ?: "",
            unitLabel = o.str("unitLabel") ?: "",
            ackBy = ack?.str("by"),
            ackAt = ack?.str("at"),
            ackCount = ack?.optInt("count", 1) ?: 0,
            resolvedBy = res?.str("by"),
            resolvedAt = res?.str("at"),
            resolvedNote = res?.str("note"),
            cancelledBy = can?.str("by"),
            cancelledAt = can?.str("at"),
            cancelReason = can?.str("reason"),
            intervalS = if (o.isNull("intervalS")) null else o.optInt("intervalS", 0).takeIf { it > 0 },
            raw = o.toString(),
        )
    }

    private fun parseAlerts(a: JSONArray?): List<SosAlertItem> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            SosAlertItem(
                id = o.str("id") ?: return@mapNotNull null,
                status = o.str("status") ?: "Active",
                category = o.str("category") ?: "Other",
                unitLabel = o.str("unitLabel") ?: "",
                dtg = o.str("dtg") ?: "",
                raisedBy = o.str("raisedBy") ?: "",
                canAct = o.optBoolean("canAct", false),
            )
        }
    }

    private fun parseCheckin(o: JSONObject?): CheckinInfo? {
        if (o == null) return null
        return CheckinInfo(
            active = o.optBoolean("active", false),
            intervalMin = if (o.isNull("intervalMin")) null else o.optInt("intervalMin", 0).takeIf { it > 0 },
            graceMin = o.optInt("graceMin", 0),
            lastAt = o.str("lastAt"),
            lastBy = o.str("lastBy"),
            lastStatus = o.str("lastStatus"),
            dueAt = o.str("dueAt"),
            overdueAt = o.str("overdueAt"),
            dueInS = if (o.isNull("dueInS") || !o.has("dueInS")) null else o.optLong("dueInS"),
            state = o.str("state") ?: "off",
            receivedMs = System.currentTimeMillis(),
            raw = o.toString(),
        )
    }
}
