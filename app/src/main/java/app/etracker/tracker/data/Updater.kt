package app.etracker.tracker.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.etracker.tracker.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the in-app update flow is. The UI renders one dialog from this. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Available(val info: Api.AppUpdate) : UpdatePhase
    data class NeedsPermission(val info: Api.AppUpdate) : UpdatePhase
    data class Downloading(val info: Api.AppUpdate, val done: Long, val total: Long) : UpdatePhase
    data class Ready(val info: Api.AppUpdate, val file: File) : UpdatePhase
    data class Failed(val info: Api.AppUpdate, val message: String) : UpdatePhase
}

/**
 * In-app updates: ask the server (token-authenticated) whether a newer APK is
 * published, download it into the app cache, verify its SHA-256 (and that Android
 * will accept it: same package, newer version, same signing key), then hand it to
 * the system installer. Nothing here touches the fix queue or the session, so
 * queued positions and reports survive a failed or successful update.
 */
object Updater {
    private val _phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = _phase.asStateFlow()

    /** Result of the last manual "Check for updates" tap (null = nothing to say). */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastCheck = 0L
    private var snoozeUntil = 0L
    private var snoozedCode = -1

    const val MIN_GAP_MS = 10 * 60 * 1000L
    private const val SNOOZE_MS = 4 * 60 * 60 * 1000L

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Checks the server (also the wipe heartbeat). Throttled to one call per
     * [MIN_GAP_MS] unless [force]. Blocking: call from a background thread/coroutine.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (DeviceReset.active) return
        val busy = _phase.value
        if (busy is UpdatePhase.Downloading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < MIN_GAP_MS) return
        val session = Session(app)
        val site = session.site ?: return
        val token = session.token ?: return
        if (!isOnline(app)) return
        lastCheck = now
        cleanCache(app)
        try {
            val r = Api.getAppUpdate(site, token, BuildConfig.VERSION_CODE)
            if (r.wipe) {
                DeviceReset.wipe(app)
                return
            }
            val u = r.update
            if (u == null || u.versionCode <= BuildConfig.VERSION_CODE) {
                if (_phase.value !is UpdatePhase.Failed) _phase.value = UpdatePhase.Idle
                return
            }
            val cur = _phase.value
            // keep an in-flight state for the same version; otherwise (re)offer it, honouring "Later"
            if (cur is UpdatePhase.Ready && cur.info.versionCode == u.versionCode) return
            if (cur is UpdatePhase.NeedsPermission && cur.info.versionCode == u.versionCode) return
            val snoozed = !u.mandatory && u.versionCode == snoozedCode && now < snoozeUntil
            _phase.value = if (snoozed) UpdatePhase.Idle else UpdatePhase.Available(u)
        } catch (e: Api.ApiException) {
            if (e.authFailure) DeviceReset.signOut(app)
            // anything else (offline, server busy): stay quiet, try again later
        } catch (_: Exception) {
        }
    }

    /** Manual check from the Status screen: ignores the throttle and snooze, and reports the outcome. */
    fun checkNow(context: Context) {
        if (_checking.value) return
        _checking.value = true
        _note.value = null
        scope.launch {
            try {
                val app = context.applicationContext
                if (!isOnline(app)) { _note.value = "No connection."; return@launch }
                snoozeUntil = 0L
                check(app, force = true)
                _note.value = when (val p = _phase.value) {
                    is UpdatePhase.Idle -> "You're on the latest version."
                    is UpdatePhase.Failed -> "Update found but the download failed."
                    else -> "Update available."
                }
            } finally {
                _checking.value = false
            }
        }
    }

    /** "Later" — not allowed for mandatory updates. */
    fun later() {
        val cur = _phase.value
        val info = when (cur) {
            is UpdatePhase.Available -> cur.info
            is UpdatePhase.Failed -> cur.info
            is UpdatePhase.NeedsPermission -> cur.info
            else -> return
        }
        if (info.mandatory) return
        snoozedCode = info.versionCode
        snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
        _phase.value = UpdatePhase.Idle
    }

    /** "Update now" (or "Try again"). */
    fun start(context: Context, info: Api.AppUpdate) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        if (!canInstall(app)) {
            _phase.value = UpdatePhase.NeedsPermission(info)
            return
        }
        job = scope.launch { download(app, info) }
    }

    /** The "install unknown apps" screen for this app. */
    fun openInstallSettings(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /** Called when the app returns to the foreground: continue if the user just granted the permission. */
    fun onResume(context: Context) {
        val app = context.applicationContext
        val cur = _phase.value
        if (cur is UpdatePhase.NeedsPermission && canInstall(app)) start(app, cur.info)
    }

    fun install(context: Context, file: File): Boolean {
        val app = context.applicationContext
        return try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun canInstall(app: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    // ---------------------------------------------------------------- download

    private fun dir(app: Context) = File(app.cacheDir, "updates").apply { mkdirs() }

    private fun download(app: Context, requested: Api.AppUpdate) {
        val session = Session(app)
        val site = session.site
        val token = session.token
        if (site == null || token == null) {
            _phase.value = UpdatePhase.Failed(requested, "This device is not enrolled.")
            return
        }
        // the offer may be stale (a newer version published since the check): ask again right before
        // downloading, and download exactly the version whose checksum we hold
        val info = try {
            Api.getAppUpdate(site, token, BuildConfig.VERSION_CODE).update ?: run {
                _phase.value = UpdatePhase.Idle
                return
            }
        } catch (e: Api.ApiException) {
            if (e.authFailure) { DeviceReset.signOut(app); return }
            requested
        } catch (_: Exception) {
            requested
        }
        if (info.versionCode != requested.versionCode) _phase.value = UpdatePhase.Available(info)
        val dest = File(dir(app), "optix-${info.versionCode}.apk")
        try {
            // a previous, verified download of this version can be reused
            if (dest.isFile && sha256(dest) == info.sha256) {
                finish(app, info, dest)
                return
            }
            dest.delete()
            val part = File(dir(app), "optix-${info.versionCode}.apk.part")
            _phase.value = UpdatePhase.Downloading(info, 0, info.size)
            val sha = Api.downloadApp(site, token, part, info.size, info.versionCode) { done, total ->
                _phase.value = UpdatePhase.Downloading(info, done, total)
            }
            if (!sha.equals(info.sha256, ignoreCase = true)) {
                part.delete()
                _phase.value = UpdatePhase.Failed(info, "The downloaded file failed verification (checksum mismatch). It was discarded; tap Check for updates, then try again.")
                return
            }
            if (!part.renameTo(dest)) {
                part.delete()
                _phase.value = UpdatePhase.Failed(info, "Could not store the download.")
                return
            }
            finish(app, info, dest)
        } catch (e: Api.ApiException) {
            when {
                e.authFailure -> DeviceReset.signOut(app)
                else -> _phase.value = UpdatePhase.Failed(info, if (e.status == 0) "Can't reach the server (${e.message}). Your reports are still queued." else Api.describe(e))
            }
        } catch (e: Exception) {
            _phase.value = UpdatePhase.Failed(info, e.message ?: "Download failed")
        }
    }

    /** The file is verified: check Android will accept it, then open the installer. */
    private fun finish(app: Context, info: Api.AppUpdate, file: File) {
        archiveProblem(app, info, file)?.let {
            file.delete()
            _phase.value = UpdatePhase.Failed(info, it)
            return
        }
        _phase.value = UpdatePhase.Ready(info, file)
        if (!install(app, file)) {
            _phase.value = UpdatePhase.Failed(info, "Could not open the system installer.")
        }
    }

    /**
     * Pre-flight on the downloaded archive so the user gets a clear message instead of
     * Android's generic "App not installed": same package, newer version, same signing key.
     * Returns null when fine (or when it cannot be determined).
     */
    @Suppress("DEPRECATION")
    private fun archiveProblem(app: Context, info: Api.AppUpdate, file: File): String? {
        return try {
            val pm = app.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "The downloaded file is not a valid app package."
            if (archive.packageName != app.packageName) return "The downloaded file is for a different app."
            val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            if (code <= BuildConfig.VERSION_CODE) return "The published app (version code $code) is not newer than the installed one."
            if (Build.VERSION.SDK_INT >= 28) {
                val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                val offered = archive.signingInfo
                if (installed != null && offered != null && offered.apkContentsSigners.isNotEmpty()) {
                    val a = installed.apkContentsSigners.map { it.toByteArray().contentHashCode() }.toSet()
                    val b = offered.apkContentsSigners.map { it.toByteArray().contentHashCode() }.toSet()
                    val overlap = a.intersect(b).isNotEmpty() ||
                        // key rotation: the new APK may carry the installed key in its history
                        (installed.apkContentsSigners.isNotEmpty() && offered.signingCertificateHistory
                            ?.any { s -> s.toByteArray().contentHashCode() in a } == true)
                    if (!overlap) return "This update is signed with a different key than the installed app, so Android cannot install it over this one. Uninstall this app, install the new version (ask your administrator for the file), then enrol again. This is needed only once."
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Remove downloads that are no longer useful (installed already or older than the build). */
    private fun cleanCache(app: Context) {
        runCatching {
            dir(app).listFiles()?.forEach { f ->
                val code = Regex("optix-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
                if (code == null || code <= BuildConfig.VERSION_CODE) f.delete()
            }
        }
    }
}
