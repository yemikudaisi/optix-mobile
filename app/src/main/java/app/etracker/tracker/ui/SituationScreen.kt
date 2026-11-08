package app.etracker.tracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.etracker.tracker.data.Api
import app.etracker.tracker.data.Session
import app.etracker.tracker.data.SituationCache
import app.etracker.tracker.data.TrackerState
import app.etracker.tracker.data.TrackerUi
import app.etracker.tracker.service.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How often the picture is refreshed while the Situation tab is open. */
private const val POLL_MS = 10_000L

/**
 * The bundled Situation map page (built from the server repo's frontend and
 * shipped in assets/situation/). It runs in a WebView so the phone draws plan
 * graphics and unit symbols with exactly the same renderer as the web app.
 * Native code owns auth and networking and pushes data in through the page's
 * `Situation` JavaScript API.
 */
class SituationWeb(context: Context) {
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    val view: WebView = WebView(context)
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var ready = false
    private val queued = ArrayList<String>()

    init {
        configure(context)
        view.loadUrl("https://appassets.androidplatform.net/assets/situation/situation.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(context: Context) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect on debug builds
        }
        // the page may have been loaded before this view had a size: tell it when it does
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) js("Situation.resize()")
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.url?.let { loader.shouldInterceptRequest(it) }

            override fun onPageFinished(v: WebView?, url: String?) {
                ready = true
                queued.forEach { view.evaluateJavascript(it, null) }
                queued.clear()
            
                if (debuggable) {
                    // debug builds: log a snapshot of the page a few seconds after load
                    view.postDelayed({ view.evaluateJavascript("Situation.debug()") { Log.d("SituationJS", "debug: $it") } }, 8000)
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("SituationJS", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    private fun js(code: String) {
        if (ready) view.evaluateJavascript(code, null) else queued.add(code)
    }

    fun setData(json: String) = js("Situation.setData(${JSONObject.quote(json)})")
    fun setSelf(lat: Double, lon: Double, accuracy: Float) = js("Situation.setSelf($lat,$lon,$accuracy)")
    fun setStatus(text: String, level: String) = js("Situation.setStatus(${JSONObject.quote(text)},'$level')")

    /** Detach from any previous parent so Compose can host it again after a tab switch. */
    fun attach(): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun destroy() {
        view.stopLoading()
        view.destroy()
    }
}

@Composable
fun SituationScreen(session: Session, web: SituationWeb, state: TrackerUi) {
    val ctx = LocalContext.current

    // the phone's own live fix, drawn as "YOU"
    LaunchedEffect(state.lat, state.lon) {
        val la = state.lat
        val lo = state.lon
        if (la != null && lo != null) web.setSelf(la, lo, state.accuracy ?: 0f)
    }

    // show the saved picture straight away, then keep it fresh while this tab is open
    LaunchedEffect(Unit) {
        var last: JSONObject? = SituationCache.load(ctx)
        last?.let {
            web.setData(it.toString())
            web.setStatus("Showing saved data…", "warn")
        }
        while (isActive) {
            val site = session.site
            val token = session.token
            if (site == null || token == null) break
            try {
                val next = withContext(Dispatchers.IO) {
                    Api.getSituation(site, token, SituationCache.modified(last, "plan"), SituationCache.modified(last, "ipb"))
                }
                val merged = SituationCache.merge(last, next)
                last = merged
                withContext(Dispatchers.IO) { SituationCache.save(ctx, merged) }
                web.setData(merged.toString())
                web.setStatus("", "ok")
            } catch (e: Api.ApiException) {
                if (e.authFailure) {
                    // the server no longer accepts this device: back to onboarding
                    session.markRevoked()
                    SituationCache.clear(ctx)
                    TrackingService.stop(ctx)
                    TrackerState.update { copy(revoked = true, status = "Revoked") }
                    break
                }
                val saved = last?.let { "Offline — showing data from ${clock(SituationCache.fileTime(ctx))}" }
                web.setStatus(saved ?: "Offline — can't reach the server", "warn")
            }
            delay(POLL_MS)
        }
    }

    AndroidView(factory = { web.attach() }, modifier = Modifier.fillMaxSize())
}

private fun clock(ms: Long): String =
    if (ms <= 0) "earlier" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
