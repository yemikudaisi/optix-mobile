package app.etracker.tracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.etracker.tracker.data.Api
import app.etracker.tracker.data.AutoReport
import app.etracker.tracker.data.CheckinInfo
import app.etracker.tracker.data.DeviceReset
import app.etracker.tracker.data.Session
import app.etracker.tracker.data.TrackerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.etracker.tracker.data.FieldOutbox
import app.etracker.tracker.data.FieldState
import app.etracker.tracker.data.FieldUi
import app.etracker.tracker.data.ReportingPrefs
import app.etracker.tracker.service.TrackingService
import app.etracker.tracker.data.OwnSos
import app.etracker.tracker.data.SosAlertItem
import app.etracker.tracker.service.FieldNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * SOS and check-in on the Status tab. Colours: the SOS red and the ALL OK green are fixed (white text
 * on them is >= 4.5:1 in both themes) so the controls look the same day and night; state words use
 * the theme's error / secondary (amber) / primary text colours.
 */

private val SosRed = Color(0xFFB91C1C)
private val SosRedDark = Color(0xFF7F1D1D)
private val OkGreen = Color(0xFF15803D)

private val CATEGORIES = listOf("Contact", "Casualty", "Vehicle", "Lost", "Other")
private val UNIT_STATUSES = listOf("Ready", "Moving", "Engaged", "Reorganising", "Not Combat Effective")

/** Everything SOS / check-in on the Status tab, in priority order. */
@Composable
fun FieldPanel() {
    val ctx = LocalContext.current
    val ui by FieldState.flow.collectAsState()
    val scope = rememberCoroutineScope()

    // a ticking clock: the local burst ends and the check-in countdown moves without a server reply
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    // with reporting stopped the service does not poll: keep the SOS / check-in picture fresh while this tab is open
    LaunchedEffect(Unit) {
        val session = Session(ctx.applicationContext)
        while (true) {
            if (!TrackerState.flow.value.running) {
                val site = session.site
                val token = session.token
                if (site != null && token != null && !DeviceReset.active) {
                    val gen = FieldState.generation()
                    try {
                        val p = withContext(Dispatchers.IO) { Api.getFieldState(site, token) }
                        if (p.wipe) DeviceReset.wipe(ctx) else withContext(Dispatchers.IO) { FieldState.applyServer(ctx, p.raw, gen) }
                    } catch (e: Api.ApiException) {
                        if (e.status == 401) DeviceReset.signOut(ctx)
                    } catch (_: Exception) {
                    }
                }
            }
            delay(if (FieldState.value.sosActive) 15_000 else 60_000)
        }
    }

    // refusals (and successes) of what this phone queued
    var sosNote by remember { mutableStateOf<String?>(null) }
    var checkinNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        FieldOutbox.results.collect { r ->
            when (r.kind) {
                "sos", "sos_cancel" -> if (!r.retry) sosNote = if (r.ok) null else "REFUSED: ${r.msg}"
                "checkin" -> if (!r.retry) checkinNote = if (r.ok) null else "REFUSED: ${r.msg}"
            }
        }
    }

    var confirm by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf(false) }

    if (ui.sosAlerts.isNotEmpty()) {
        SubordinateSosCard(ui.sosAlerts)
        Spacer(Modifier.height(16.dp))
    }

    OwnSosBanner(ui, now, onCancel = { cancelDialog = true })

    SosControl(
        onHeld = { countdown = true },
        onTap = { confirm = true },
    )
    sosNote?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))

    CheckinCard(ui, now, checkinNote) { status ->
        checkinNote = null
        if (ReportingPrefs.mode == AutoReport.CHECKIN) TrackingService.startIfPermitted(ctx)
        FieldOutbox.checkIn(ctx, status)
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Raise SOS?", fontWeight = FontWeight.Bold) },
            text = { Text("Your unit and its chain of command are alerted with your position. You get 5 seconds to cancel.") },
            confirmButton = {
                Button(
                    onClick = { confirm = false; countdown = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                ) { Text("RAISE SOS", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    if (countdown) {
        SosCountdown(
            onCancel = { countdown = false },
            onSend = { category, note ->
                countdown = false
                sosNote = null
                FieldNotifier.buzzSent(ctx)
                scope.launch { FieldOutbox.raiseSos(ctx, category, note) }
            },
        )
    }

    if (cancelDialog) {
        CancelSosDialog(
            onDismiss = { cancelDialog = false },
            onConfirm = { reason ->
                cancelDialog = false
                sosNote = null
                FieldOutbox.cancelSos(ctx, ui.sos?.takeIf { it.open }?.id, reason)
            },
        )
    }
}

// ---------------------------------------------------------------- SOS button

/**
 * The red SOS button: hold 2 s (a ring fills round it; letting go early cancels) or tap for a
 * confirmation. Either way the 5-second countdown follows, so a pocket press cannot send an SOS.
 */
@Composable
private fun SosControl(onHeld: () -> Unit, onTap: () -> Unit) {
    val held by rememberUpdatedState(onHeld)
    val tapped by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(SosRed)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val t0 = System.currentTimeMillis()
                        var fired = false
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(durationMillis = 2000, easing = LinearEasing))
                            fired = true
                            held()
                        }
                        val up = waitForUpOrCancellation()
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                        // a quick tap asks for confirmation; a hold let go early does nothing
                        if (!fired && up != null && System.currentTimeMillis() - t0 < 400) tapped()
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "SOS. Hold for 2 seconds, or tap to confirm."
                    onClick { tapped(); true }
                },
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val w = 7.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(w))
                if (progress.value > 0f) {
                    drawArc(Color.White, -90f, 360f * progress.value, false, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            Text("SOS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("EMERGENCY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Hold 2 s, or tap and confirm. You then have 5 s to cancel.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sends your position to your chain of command, even without signal (it goes when there is).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- countdown

/** Full-width 5-second countdown: a very big CANCEL, one-tap categories and an optional note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SosCountdown(onCancel: () -> Unit, onSend: (category: String, note: String) -> Unit) {
    var left by remember { mutableIntStateOf(5) }
    var category by remember { mutableStateOf("Other") }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(onSend)

    fun fire() {
        if (sent) return
        sent = true
        send(category, note)
    }

    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000)
            left--
        }
        fire()
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().background(SosRed).padding(vertical = 16.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("SENDING SOS IN", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$left", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black, lineHeight = 76.sp)
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    ) { Text("CANCEL", fontSize = 32.sp, fontWeight = FontWeight.Black) }
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CATEGORIES.forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(c.uppercase(), fontWeight = FontWeight.Bold) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(300) },
                        label = { Text("Note (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = ::fire, modifier = Modifier.align(Alignment.End)) {
                        Text("SEND NOW", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- own SOS banner

/** This phone's SOS: queued / active (acknowledged or not), then briefly how it ended. */
@Composable
private fun OwnSosBanner(ui: FieldUi, now: Long, onCancel: () -> Unit) {
    val sos = ui.sos
    var dismissed by remember { mutableStateOf<String?>(null) }

    if (ui.sosActive) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SosRed, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sos != null && sos.open) {
                    Text("SOS ACTIVE", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Sent ${sos.dtg} · ${sos.category.uppercase()}", fontWeight = FontWeight.Bold)
                    Text(
                        if (sos.ackBy != null) "ACKNOWLEDGED by ${sos.ackBy}${sos.ackAt?.let { " ${hhmm(it)}" } ?: ""}"
                        else "NOT ACKNOWLEDGED",
                        fontWeight = FontWeight.Bold,
                    )
                    if (ui.sosQueued) Text("Repeat SOS queued — sending when there is signal")
                } else {
                    Text("SOS QUEUED", fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Sending when there is signal", fontWeight = FontWeight.Bold)
                }
                if (ui.burst(now)) Text("Reporting position every ${ui.burstIntervalS} s")
                if (ui.cancelQueued) {
                    Text("CANCEL QUEUED — sending when there is signal", fontWeight = FontWeight.Bold)
                } else {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel (false alarm)") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    } else if (sos != null && !sos.open && dismissed != sos.id) {
        ClosedSosCard(sos) { dismissed = sos.id }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ClosedSosCard(sos: OwnSos, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val resolved = sos.status == "Resolved"
            Text(
                if (resolved) "SOS ${sos.dtg} RESOLVED" else "SOS ${sos.dtg} ${sos.status.uppercase()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (resolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (resolved) {
                Text(listOfNotNull(sos.resolvedBy?.let { "by $it" }, sos.resolvedAt?.let(::hhmm)).joinToString(" "))
                sos.resolvedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else if (sos.status == "Cancelled") {
                Text(listOfNotNull(sos.cancelledBy?.let { "by $it" }, sos.cancelledAt?.let(::hhmm)).joinToString(" "))
                sos.cancelReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun CancelSosDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel SOS?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only for a false alarm. Your chain of command sees the reason.")
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = { Text("Reason (required)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                enabled = reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
            ) { Text("CANCEL SOS", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep SOS") } },
    )
}

// ---------------------------------------------------------------- subordinate SOS

@Composable
private fun SubordinateSosCard(alerts: List<SosAlertItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SosRedDark, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (alerts.size == 1) "SOS IN YOUR COMMAND" else "${alerts.size} SOS IN YOUR COMMAND", fontSize = 20.sp, fontWeight = FontWeight.Black)
            alerts.forEach { a ->
                Column {
                    Text(
                        "SOS ${a.unitLabel.ifBlank { "—" }} · ${a.category.uppercase()} · ${a.dtg} · ${a.status.uppercase()}",
                        fontWeight = FontWeight.Bold,
                    )
                    if (a.raisedBy.isNotBlank()) Text(a.raisedBy, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Open Situation to see it on the map and acknowledge.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- check-in

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckinCard(ui: FieldUi, now: Long, note: String?, onCheckIn: (String?) -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
    val ck = ui.checkin
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CHECK-IN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            CheckinStateLine(ck, now)
            ck?.lastAt?.let { at ->
                Text(
                    "Last: ${hhmm(at)}${ck.lastBy?.let { " by $it" } ?: ""}${ck.lastStatus?.let { " · ${it.uppercase()}" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Unit status (optional)", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UNIT_STATUSES.forEach { s ->
                    FilterChip(
                        selected = status == s,
                        onClick = { status = if (status == s) null else s },
                        label = { Text(s) },
                    )
                }
            }
            Button(
                onClick = { onCheckIn(status); status = null },
                enabled = !ui.checkinQueued,
                colors = ButtonDefaults.buttonColors(containerColor = OkGreen, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text("CHECK IN — ALL OK", fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            when {
                ui.checkinQueued -> Text("Check-in queued — sending when there is signal", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                note != null -> Text(note, color = MaterialTheme.colorScheme.error)
                ui.lastCheckinDtg != null -> Text("CHECKED IN ${ui.lastCheckinDtg}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CheckinStateLine(ck: CheckinInfo?, now: Long) {
    when {
        ck == null || !ck.active || ck.state == "off" -> Text(
            "No check-in schedule",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ck.state == "overdue" -> Text("CHECK-IN OVERDUE", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
        ck.state == "due" -> Text("CHECK-IN DUE", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
        else -> {
            val s = ck.dueInNow(now)
            Text(
                when {
                    s == null -> "Check-in up to date"
                    s <= 0 -> "Next check-in due now"
                    else -> "Next check-in due in ${duration(s)}"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 25 min / 1 h 40 min (rounded up: "due in 1 min" until it is due). */
private fun duration(s: Long): String {
    val m = (s + 59) / 60
    return if (m < 60) "$m min" else "${m / 60} h${if (m % 60 != 0L) " ${m % 60} min" else ""}"
}

/** A server-local timestamp ("2026-11-12 15:30:12.123") as HH:mm; anything else as given. */
private fun hhmm(s: String): String =
    if (s.length >= 16 && s[10] == ' ' && s[13] == ':') s.substring(11, 16) else s
