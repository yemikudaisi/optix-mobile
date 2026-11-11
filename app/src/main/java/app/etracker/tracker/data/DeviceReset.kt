package app.etracker.tracker.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.etracker.tracker.service.TrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote wipe and clean sign-out.
 *
 * [wipe] runs when the server says `wipe: true` (in any reply: positions,
 * situation, report, update check). It erases the *app's own* data only — not the
 * phone — and does not depend on the network: the token is remembered in a tiny
 * encrypted record purely so the acknowledgement can be (re)sent; the server revokes
 * the token when it arrives. Order matters: the foreground service is stopped last,
 * so the process stays alive while the wipe runs.
 */
object DeviceReset {
    private val running = AtomicBoolean(false)

    /** True while a wipe is in progress: nothing may record or send anything. */
    @Volatile var active = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // outlives the service/UI that triggered it

    // ------------------------------------------------------------ the little record that survives a wipe
    private const val STATE_FILE = "reset_state"
    private const val KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs__"

    private fun state(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, STATE_FILE,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Show "reset by an administrator" on the onboarding screen. */
    fun hasNotice(ctx: Context): Boolean = runCatching { state(ctx.applicationContext).getBoolean("notice", false) }.getOrDefault(false)

    /** Called once the phone is enrolled again: the old notice and any unsent acknowledgement are moot. */
    fun clearNotice(ctx: Context) {
        runCatching { state(ctx.applicationContext).edit().clear().commit() }
    }

    // ------------------------------------------------------------ wipe
    /** Start the wipe (idempotent, returns at once). Safe to call from any thread. */
    fun wipe(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        active = true
        scope.launch {
            try {
                withContext(NonCancellable) { doWipe(app) }
            } finally {
                active = false
                running.set(false)
            }
        }
    }

    /** At app start: finish a wipe that was interrupted (process killed), and retry an unsent acknowledgement. */
    fun resume(context: Context) {
        val app = context.applicationContext
        val st = runCatching { state(app) }.getOrNull() ?: return
        if (st.getBoolean("in_progress", false)) {
            wipe(app)
        } else if (st.getString("ack_token", null) != null) {
            scope.launch { sendAck(app) }
        }
    }

    private suspend fun doWipe(app: Context) {
        // 1. remember only what is needed to acknowledge; mark the wipe as under way (so it resumes if we are killed)
        val sess = Session(app)
        val st = state(app)
        val site = sess.site
        val token = sess.token
        st.edit().apply {
            putBoolean("in_progress", true)
            putBoolean("notice", true)
            if (site != null && token != null) { putString("ack_site", site); putString("ack_token", token) }
        }.commit()

        // 2. stop reporting (location updates are guarded by [active]; the service itself is stopped last)
        // 3. erase local data
        sess.wipe()
        FixQueue.wipe(app)
        SituationCache.clear(app)
        MapPacks.clear(app) // close the open map files before they are erased
        eraseFiles(app)
        clearOtherPrefs(app)
        clearWebStorage(app)

        // 4. tell the UI: back to onboarding with a neutral message
        TrackerState.update { TrackerUi(wiped = true, status = "Reset") }

        // 5. stop the service now that nothing is left to protect, then a second sweep of the WebView
        // (the page may have written its outbox again before it was torn down)
        TrackingService.stop(app)
        delay(2_500)
        clearWebStorage(app)
        eraseFiles(app)
        st.edit().putBoolean("in_progress", false).commit()

        // 6. best-effort acknowledgement; retried at the next app start if it fails
        sendAck(app)
    }

    private fun sendAck(app: Context) {
        val st = runCatching { state(app) }.getOrNull() ?: return
        val site = st.getString("ack_site", null)
        val token = st.getString("ack_token", null)
        if (site == null || token == null) return
        try {
            Api.ackWipe(site, token)
            st.edit().remove("ack_site").remove("ack_token").commit()
        } catch (e: Api.ApiException) {
            // 401/403 = the server has already revoked this token (acknowledged earlier): nothing left to send
            if (e.authFailure) st.edit().remove("ack_site").remove("ack_token").commit()
        } catch (_: Exception) {
        }
    }

    private fun eraseFiles(app: Context) {
        val dirs = listOfNotNull(
            app.filesDir, app.cacheDir, app.codeCacheDir, app.noBackupFilesDir,
            app.externalCacheDir,
        ) + app.getExternalFilesDirs(null).filterNotNull() + app.externalCacheDirs.filterNotNull()
        dirs.distinct().forEach { runCatching { it.listFiles()?.forEach { f -> f.deleteRecursively() } } }
        runCatching { app.databaseList().filter { it != "fixes.db" }.forEach { app.deleteDatabase(it) } }
    }

    /** Empties every SharedPreferences file except the crypto keyset and our own record (deleting those would break re-enrolment). */
    private fun clearOtherPrefs(app: Context) {
        val dir = File(app.applicationInfo.dataDir, "shared_prefs")
        dir.list()?.map { it.removeSuffix(".xml") }
            ?.filter { it != KEYSET_FILE && it != STATE_FILE }
            ?.forEach { runCatching { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    }

    /** localStorage (task / target outbox), cookies, HTTP cache and form data of the Situation page. */
    private fun clearWebStorage(app: Context) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                WebViewDatabase.getInstance(app).apply { clearHttpAuthUsernamePassword(); clearFormData() }
                WebView(app).apply { clearCache(true); clearHistory(); clearFormData(); destroy() }
            }
        }
    }

    // ------------------------------------------------------------ revoked
    /**
     * The server no longer accepts our token (revoked, not wiped): stop cleanly and
     * return to onboarding. The server address is kept so re-enrolling is quick.
     */
    fun signOut(context: Context) {
        val app = context.applicationContext
        if (active) return
        val session = Session(app)
        if (!session.enrolled) return
        session.markRevoked()
        FixQueue.get(app).clear()
        SituationCache.clear(app)
        MapPacks.clear(app) // the operation's maps are not left on a phone the server has dropped
        TrackerState.update { copy(revoked = true, status = "Revoked") }
        TrackingService.stop(app)
    }
}
