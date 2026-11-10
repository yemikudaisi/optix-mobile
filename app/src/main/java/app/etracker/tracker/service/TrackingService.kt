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
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: Session
    private lateinit var queue: FixQueue
    private lateinit var fused: FusedLocationProviderClient
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = Session(applicationContext)
        queue = FixQueue.get(applicationContext)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
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

    // ---- location -------------------------------------------------------

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun beginLocationUpdates() {
        val intervalMs = session.intervalSeconds * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
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
        fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- delivery -------------------------------------------------------

    private fun startFlusher() {
        scope.launch {
            var failures = 0
            while (isActive && !DeviceReset.active) {
                // wake on a new fix, or at least once per reporting interval
                withTimeoutOrNull(session.intervalSeconds * 1000L) { flushSignal.receive() }
                if (DeviceReset.active) break
                // heartbeat + update check (throttled): lets the server reach us even when no fixes are flowing
                Updater.check(applicationContext)
                if (flushOnce()) {
                    failures = 0
                } else {
                    failures++
                    delay(minOf(300_000L, 5_000L shl minOf(failures, 6))) // 10s .. 5min
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
                val pulse = Api.postPositions(site, token, batch.map { it.fix })
                val suggested = pulse.intervalSeconds
                if (pulse.wipe) {
                    // the server wants this device wiped: erase everything (the queue included) and stop
                    DeviceReset.wipe(applicationContext)
                    return true
                }
                queue.delete(batch.map { it.id })
                if (suggested in 5..3600 && suggested != session.intervalSeconds) session.updateInterval(suggested)
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

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reporting position")
            .setContentText(session.unit.ifBlank { session.label })
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val BATCH = 200 // server limit per call

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
