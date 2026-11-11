package app.etracker.tracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.etracker.tracker.MainActivity
import app.etracker.tracker.R
import app.etracker.tracker.data.Api
import app.etracker.tracker.data.DeviceReset
import app.etracker.tracker.data.FieldOutbox
import app.etracker.tracker.data.FieldState
import app.etracker.tracker.data.FieldUi
import app.etracker.tracker.data.Fix
import app.etracker.tracker.data.FixQueue
import app.etracker.tracker.data.Session
import app.etracker.tracker.data.TrackerState
import app.etracker.tracker.data.Updater
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that records GPS fixes and delivers them to the server.
 *
 * Every fix goes into the durable [FixQueue] first; a flusher coroutine sends
 * the queue in batches of up to 200 (oldest first) and only deletes what the
 * server accepted. Offline or server errors just leave the queue to grow and
 * retry with backoff. If the server says the token is no longer valid (device
 * revoked) the service stops and the app returns to onboarding.
 *
 * SOS and check-in ride on the same service: the flusher sends the [FieldOutbox]
 * (SOS first) before any fixes, every reply's field state goes into [FieldState],
 * and while this phone's SOS is in its burst the fix interval drops to the SOS
 * interval (the location request is rebuilt whenever the effective interval
 * changes). With no fixes going out it polls get_field_state, so a commander's
 * phone in a pocket still hears of a subordinate's SOS and a check-in falling due.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: Session
    private lateinit var queue: FixQueue
    private lateinit var fused: FusedLocationProviderClient
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private var callback: LocationCallback? = null

    /** The interval the current location request was built with (main thread). */
    private var requestedIntervalS = 0

    /** Last time the server heard from us with a reply carrying the field state (positions or a poll). */
    @Volatile private var lastContactMs = 0L

    /** Last foreground notification shown, so it is only re-posted when it changes. */
    private var shownNotice: Pair<String, String>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = Session(applicationContext)
        queue = FixQueue.get(applicationContext)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        FieldNotifier.createChannels(applicationContext)
        FieldOutbox.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!session.enrolled || !hasLocationPermission() || DeviceReset.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        // a foreground service must call startForeground promptly
        startForegroundCompat()
        if (callback == null) {
            beginLocationUpdates()
            startFlusher()
            startFieldWatch()
            startFieldPoll()
            TrackerState.update { copy(running = true, status = "Waiting for GPS…", revoked = false, queued = queue.count()) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        scope.cancel()
        TrackerState.update { copy(running = false, status = if (revoked) "Revoked" else "Stopped") }
        super.onDestroy()
    }

    // ---- interval -------------------------------------------------------

    /**
     * The interval to report at now: the SOS interval while this phone's SOS is in its burst
     * (locally from the moment it was raised, or as the server says), else the normal one.
     */
    private fun effectiveIntervalS(ui: FieldUi = FieldState.value): Int =
        if (ui.burst()) minOf(ui.burstIntervalS, session.intervalSeconds) else session.intervalSeconds

    // ---- location -------------------------------------------------------

    private fun beginLocationUpdates() {
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (DeviceReset.active) return // wipe in progress: record nothing
                result.locations.forEach { loc ->
                    // never queue something the server would reject
                    if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return@forEach
                    queue.add(
                        Fix(
                            ts = loc.time,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                            speed = if (loc.hasSpeed()) loc.speed else null,
                            heading = if (loc.hasBearing()) loc.bearing else null,
                        ),
                    )
                    TrackerState.update {
                        copy(
                            lastFixMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                            queued = queue.count(),
                        )
                    }
                }
                flushSignal.trySend(Unit)
            }
        }
        callback = cb
        requestUpdates(effectiveIntervalS())
    }

    /** (Re)builds the location request for [intervalS]; the same callback keeps receiving. Main thread. */
    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun requestUpdates(intervalS: Int) {
        val cb = callback ?: return
        if (requestedIntervalS != 0) fused.removeLocationUpdates(cb)
        requestedIntervalS = intervalS
        val intervalMs = intervalS * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    private fun hasLocationPermission() = hasLocationPermission(this)

    // ---- SOS / check-in -------------------------------------------------

    /**
     * Follows [FieldState] (and the clock, for the end of the local burst): rebuilds the location
     * request when the effective interval changes and keeps the foreground notification current.
     */
    private fun startFieldWatch() {
        scope.launch(Dispatchers.Main) {
            FieldState.flow.collect { onField(it) }
        }
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5_000)
                onField(FieldState.value)
            }
        }
    }

    private fun onField(ui: FieldUi) {
        if (callback == null || DeviceReset.active) return
        val iv = effectiveIntervalS(ui)
        if (iv != requestedIntervalS) {
            requestUpdates(iv)
            flushSignal.trySend(Unit) // the flusher's wait follows the new interval
        }
        val n = notice(ui)
        if (n != shownNotice) {
            shownNotice = n
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(n)) }
        }
    }

    /**
     * get_field_state when nothing else has reached the server lately: every 60 s, every 15 s
     * while this phone's SOS is open (to show the acknowledgement promptly).
     */
    private fun startFieldPoll() {
        scope.launch {
            var lastPoll = 0L
            while (isActive && !DeviceReset.active) {
                delay(5_000)
                val period = if (FieldState.value.sosActive) 15_000L else 60_000L
                val now = System.currentTimeMillis()
                if (now - lastContactMs < period || now - lastPoll < period) continue
                lastPoll = now
                val site = session.site ?: continue
                val token = session.token ?: continue
                val gen = FieldState.generation()
                try {
                    val p = Api.getFieldState(site, token)
                    if (p.wipe) {
                        DeviceReset.wipe(applicationContext)
                        break
                    }
                    lastContactMs = System.currentTimeMillis()
                    FieldState.applyServer(applicationContext, p.raw, gen)
                    noteInterval(p)
                } catch (e: Api.ApiException) {
                    if (e.status == 401) {
                        DeviceReset.signOut(applicationContext)
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Honour the server's suggested normal interval; the SOS interval is never stored as the normal one. */
    private fun noteInterval(p: Api.Pulse) {
        val suggested = p.intervalSeconds
        if (p.sosBurst) return
        if (suggested in 5..3600 && suggested != session.intervalSeconds) session.updateInterval(suggested)
    }

    // ---- delivery -------------------------------------------------------

    private fun startFlusher() {
        scope.launch {
            var failures = 0
            while (isActive && !DeviceReset.active) {
                // wake on a new fix, or at least once per reporting interval
                withTimeoutOrNull(effectiveIntervalS() * 1000L) { flushSignal.receive() }
                if (DeviceReset.active) break
                // SOS / check-in first: they must never wait behind a position backlog
                FieldOutbox.sendIfIdle(applicationContext)
                // heartbeat + update check (throttled): lets the server reach us even when no fixes are flowing
                Updater.check(applicationContext)
                if (flushOnce()) {
                    failures = 0
                } else {
                    failures++
                    // during an SOS keep trying at least every 10 s
                    val cap = if (FieldState.value.burst()) 10_000L else 300_000L
                    delay(minOf(cap, 5_000L shl minOf(failures, 6))) // 10s .. 5min
                }
            }
        }
    }

    /** Sends the queue. Returns false if delivery should be retried later. */
    private fun flushOnce(): Boolean {
        val site = session.site ?: return true
        val token = session.token ?: return true
        while (true) {
            val batch = queue.peek(BATCH)
            if (batch.isEmpty()) {
                TrackerState.update { copy(queued = 0) }
                return true
            }
            try {
                val gen = FieldState.generation()
                val pulse = Api.postPositions(site, token, batch.map { it.fix })
                if (pulse.wipe) {
                    // the server wants this device wiped: erase everything (the queue included) and stop
                    DeviceReset.wipe(applicationContext)
                    return true
                }
                queue.delete(batch.map { it.id })
                lastContactMs = System.currentTimeMillis()
                FieldState.applyServer(applicationContext, pulse.raw, gen)
                noteInterval(pulse)
                TrackerState.update {
                    copy(status = "Reporting", lastSentMs = System.currentTimeMillis(), queued = queue.count())
                }
            } catch (e: Api.ApiException) {
                when {
                    e.authFailure -> {
                        DeviceReset.signOut(applicationContext) // clean sign-out; also stops this service
                        return true
                    }
                    e.retryable -> {
                        TrackerState.update { copy(status = "Offline — queuing", queued = queue.count()) }
                        return false
                    }
                    else -> {
                        // the server rejected this batch outright; drop it so it can't block the queue
                        queue.delete(batch.map { it.id })
                        TrackerState.update { copy(queued = queue.count()) }
                    }
                }
            }
        }
    }

    // ---- notification ---------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** Title and text of the ongoing notification: the SOS state when there is one, else plain reporting. */
    private fun notice(ui: FieldUi): Pair<String, String> {
        val sos = ui.sos
        val unit = session.unit.ifBlank { session.label }
        return when {
            sos != null && sos.open -> {
                val ack = if (sos.ackBy != null) "ACKNOWLEDGED by ${sos.ackBy}" else "NOT ACKNOWLEDGED"
                val burst = if (ui.burst()) " · reporting every ${effectiveIntervalS(ui)} s" else ""
                "SOS ACTIVE" to "Sent ${sos.dtg} · $ack$burst"
            }
            ui.sosQueued -> "SOS QUEUED — sending when there is signal" to "Reporting every ${effectiveIntervalS(ui)} s · $unit"
            else -> "Reporting position" to unit
        }
    }

    private fun notification(n: Pair<String, String> = notice(FieldState.value)): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sos = n.first != "Reporting position"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.first)
            .setContentText(n.second)
            .apply { if (sos) setColor(0xFFB91C1C.toInt()).setColorized(true) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat() {
        val n = notice(FieldState.value)
        shownNotice = n
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(n), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification(n))
        }
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val BATCH = 200 // server limit per call

        fun hasLocationPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        /**
         * Starts reporting if location is permitted (an SOS starts it even if the holder had stopped
         * it). Without the permission nothing starts: the SOS still goes through the outbox, with the
         * last known position or none. Never throws (a background start may be refused by Android).
         */
        fun startIfPermitted(context: Context) {
            if (!hasLocationPermission(context) || DeviceReset.active || TrackerState.flow.value.running) return
            runCatching { start(context) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
