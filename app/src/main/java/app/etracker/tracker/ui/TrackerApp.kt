package app.etracker.tracker.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.etracker.tracker.data.Api
import app.etracker.tracker.data.FixQueue
import app.etracker.tracker.data.SituationCache
import app.etracker.tracker.data.Session
import app.etracker.tracker.data.TrackerState
import app.etracker.tracker.service.TrackingService
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackerApp() {
    val ctx = LocalContext.current
    val session = remember { Session(ctx.applicationContext) }
    var enrolled by remember { mutableStateOf(session.enrolled) }
    var tab by remember { mutableStateOf(0) } // 0 = status, 1 = situation map
    val state by TrackerState.flow.collectAsState()

    // the service (or the situation poll) flips this when the server revokes the device
    LaunchedEffect(state.revoked) { if (state.revoked) enrolled = session.enrolled }

    // the situation map's WebView lives as long as the device is enrolled, so
    // switching tabs doesn't reload it
    val web = if (enrolled) remember { SituationWeb(ctx.applicationContext) } else null
    DisposableEffect(web) { onDispose { web?.destroy() } }

    Scaffold(
        bottomBar = {
            if (enrolled) {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("◉") }, label = { Text("Status") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("◫") }, label = { Text("Situation") })
                }
            }
        },
    ) { padding ->
        if (enrolled && tab == 1 && web != null) {
            Column(Modifier.fillMaxSize().padding(padding)) { SituationScreen(session, web, state) }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (!enrolled) {
                    OnboardingScreen(session, revoked = session.revoked) { enrolled = true; tab = 0 }
                } else {
                    StatusScreen(session) { enrolled = false }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
private fun OnboardingScreen(session: Session, revoked: Boolean, onEnrolled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf(session.lastSite ?: "https://") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun enrol(s: String, c: String) {
        scope.launch {
            busy = true
            error = null
            try {
                val site0 = Api.normalizeSite(s)
                val e = withContext(Dispatchers.IO) { Api.enrol(site0, c) }
                session.save(site0, e)
                onEnrolled()
            } catch (ex: Exception) {
                error = Api.describe(ex)
            } finally {
                busy = false
            }
        }
    }

    fun scan() {
        error = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { barcode ->
                try {
                    // QR payload from the web app: {"v":1,"site":"https://…","code":"ABCD1234"}
                    val o = JSONObject(barcode.rawValue ?: "")
                    site = o.getString("site")
                    code = o.getString("code")
                    enrol(site, code)
                } catch (e: Exception) {
                    error = "That QR code isn't an eTracker enrolment code."
                }
            }
            .addOnFailureListener { error = it.message ?: "Scanning failed or was cancelled." }
    }

    Text("Enrol this device", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Ask your operator for an enrolment code in the eTracker web app (Devices), then scan the QR or type the code.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (revoked) {
        Spacer(Modifier.height(12.dp))
        Text(
            "This device was revoked by the server. Ask for a new enrolment code.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(20.dp))

    OutlinedButton(onClick = ::scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
    Spacer(Modifier.height(16.dp))
    Text("or enter it manually", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = site, onValueChange = { site = it }, label = { Text("Server") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code, onValueChange = { code = it.uppercase() }, label = { Text("Enrolment code") }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { enrol(site, code) },
        enabled = !busy && code.isNotBlank() && site.length > 8,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Enrolling…" else "Enrol") }
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

// -------------------------------------------------------------------- status

@Composable
private fun StatusScreen(session: Session, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val state by TrackerState.flow.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val location = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (location) {
            permissionMessage = null
            TrackingService.start(ctx)
        } else {
            permissionMessage = "Location permission is needed to report your position."
        }
    }

    fun startReporting() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
    }

    Text(session.unit.ifBlank { session.label }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("${session.label} · ${session.device}", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.status.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    state.running && state.status == "Reporting" -> MaterialTheme.colorScheme.primary
                    state.running -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row { Label("Last fix"); Mono(state.lastFixMs?.let(::clock) ?: "—") }
            Row { Label("Position"); Mono(if (state.lat != null) "%.5f, %.5f".format(state.lat, state.lon) else "—") }
            Row { Label("Accuracy"); Mono(state.accuracy?.let { "±${it.toInt()} m" } ?: "—") }
            Row { Label("Last sent"); Mono(state.lastSentMs?.let(::clock) ?: "—") }
            Row { Label("Queued"); Mono("${state.queued} fix${if (state.queued == 1) "" else "es"}") }
        }
    }
    Spacer(Modifier.height(16.dp))

    if (state.running) {
        OutlinedButton(onClick = { TrackingService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Stop reporting") }
    } else {
        Button(onClick = ::startReporting, modifier = Modifier.fillMaxWidth()) { Text("Start reporting") }
    }
    permissionMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Reports every ${session.intervalSeconds} s while running, and keeps a queue when you're offline. " +
            "For best results, exclude this app from battery optimisation.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(24.dp))
    TextButton(onClick = { confirmSignOut = true }) { Text("Sign out of this device") }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Reporting stops and queued positions are discarded. You'll need a new enrolment code to report again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    TrackingService.stop(ctx)
                    FixQueue.get(ctx).clear()
                    SituationCache.clear(ctx)
                    session.clear()
                    onSignedOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, modifier = Modifier.padding(end = 12.dp).fillMaxWidth(0.35f), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
