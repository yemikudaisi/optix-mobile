package app.etracker.tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.etracker.tracker.MainActivity
import app.etracker.tracker.R
import app.etracker.tracker.data.FieldUi

/**
 * The SOS / check-in notifications that must reach a holder whose phone is in a pocket:
 *
 * - a subordinate's SOS (a new id in `sosAlerts`): high-importance "Emergency" notification with a
 *   strong vibration, once per alert id. Seen ids are remembered in a small prefs file so neither
 *   the service poll nor the Situation poll (both feed [FieldState]) nor a restart buzz again;
 * - the unit's check-in falling due / overdue: high-importance "Check-in" notification, once per
 *   due time and state, cancelled when the state is ok again.
 *
 * Called from [app.etracker.tracker.data.FieldState] on every server update, on whatever thread.
 */
object FieldNotifier {
    const val CHANNEL_EMERGENCY = "emergency"
    const val CHANNEL_CHECKIN = "checkin"
    private const val PREFS = "field_notify"
    private const val CHECKIN_ID = 2
    private const val ALERT_BASE = 1000

    /** Long-short pulses: unmistakable even through webbing. */
    private val ALARM_PATTERN = longArrayOf(0, 900, 250, 900, 250, 900, 250, 1500)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EMERGENCY, ctx.getString(R.string.channel_emergency), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SOS raised by your unit or a unit under command"
                enableVibration(true)
                vibrationPattern = ALARM_PATTERN
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKIN, ctx.getString(R.string.channel_checkin), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your unit's check-in is due or overdue"
                enableVibration(true)
            },
        )
    }

    @Synchronized
    fun onFieldState(ctx: Context, ui: FieldUi) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        createChannels(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // ---- subordinate SOS
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val current = ui.sosAlerts.map { it.id }.toSet()
        var buzz = false
        ui.sosAlerts.forEach { a ->
            if (a.id in seen) return@forEach
            seen += a.id
            buzz = true
            val n = NotificationCompat.Builder(app, CHANNEL_EMERGENCY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SOS — ${a.unitLabel.ifBlank { "UNKNOWN UNIT" }} — ${a.category.uppercase()}")
                .setContentText(listOf(a.dtg, a.raisedBy).filter { it.isNotBlank() }.joinToString(" · "))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        listOf(a.dtg, a.raisedBy).filter { it.isNotBlank() }.joinToString(" · ") +
                            "\nOpen Situation to see it on the map" + if (a.canAct) " and acknowledge." else ".",
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(ALARM_PATTERN)
                .setAutoCancel(true)
                .setContentIntent(openApp(app))
                .build()
            runCatching { nm.notify(alertNotificationId(a.id), n) }
        }
        // an alert that is no longer open (acknowledged ones stay listed while open): take its notification down
        seen.filter { it !in current }.forEach { runCatching { nm.cancel(alertNotificationId(it)) } }
        // keep the memory small; closed ids are never listed again, so only the open ones matter
        val keep = if (seen.size > 200) seen.filter { it in current }.toMutableSet() else seen
        prefs.edit().putStringSet("seen", keep).apply()
        if (buzz) vibrate(app, ALARM_PATTERN)

        // ---- check-in reminder
        val ck = ui.checkin
        val st = ck?.state
        if (ck != null && ck.active && (st == "due" || st == "overdue")) {
            val key = "$st|${ck.dueAt}"
            if (prefs.getString("checkin_key", null) != key) {
                prefs.edit().putString("checkin_key", key).apply()
                val n = NotificationCompat.Builder(app, CHANNEL_CHECKIN)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (st == "overdue") "CHECK-IN OVERDUE" else "CHECK-IN DUE")
                    .setContentText("Open OPTIX Field and tap CHECK IN — ALL OK")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(app))
                    .build()
                runCatching { nm.notify(CHECKIN_ID, n) }
            }
        } else if (ck == null || st == "ok" || st == "off" || !ck.active) {
            if (prefs.getString("checkin_key", null) != null) prefs.edit().remove("checkin_key").apply()
            runCatching { nm.cancel(CHECKIN_ID) }
        }
    }

    /** Sign-out / wipe: take the notifications down and forget what was seen. */
    @Synchronized
    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nm = app.getSystemService(NotificationManager::class.java)
        prefs.getStringSet("seen", emptySet())?.forEach { runCatching { nm?.cancel(alertNotificationId(it)) } }
        runCatching { nm?.cancel(CHECKIN_ID) }
        prefs.edit().clear().apply()
    }

    /** A short buzz: the SOS has been sent (queued for sending). */
    fun buzzSent(ctx: Context) = vibrate(ctx.applicationContext, longArrayOf(0, 120, 80, 120, 80, 400))

    private fun vibrate(ctx: Context, pattern: LongArray) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun alertNotificationId(id: String) = ALERT_BASE + (id.hashCode() and 0x7fff)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
