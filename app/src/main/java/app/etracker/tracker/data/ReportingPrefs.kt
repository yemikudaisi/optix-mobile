package app.etracker.tracker.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * When position reporting starts by itself. A report or check-in sent while reporting is stopped has
 * no position (and the Situation form's MY POSITION is greyed out), so the default is to start with
 * the app. An SOS always starts reporting, whatever is chosen here.
 */
enum class AutoReport(val label: String, val hint: String) {
    OPEN("When the app opens", "Recommended. Reporting is on whenever you are in the app, so reports and check-ins carry your position."),
    CHECKIN("When I check in", "Reporting starts with your next check-in. That check-in itself may go without a position while the first fix is found."),
    MANUAL("Only when I tap Start reporting", "Nothing starts by itself. Reports sent before you start have no position."),
}

/**
 * The auto-reporting choice. Not sensitive, so plain SharedPreferences (it survives sign-out, like the
 * theme: it is a preference of the phone, not of the enrolment).
 */
object ReportingPrefs {
    private const val FILE = "reporting_prefs"
    private const val KEY = "auto_report"

    var mode by mutableStateOf(AutoReport.OPEN)
        private set

    /** True once this run of the app has applied the "when the app opens" start, so stopping reporting by hand sticks until the app is opened again. */
    @Volatile
    var openHandled = false

    fun load(context: Context) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        mode = AutoReport.values().firstOrNull { it.name == saved } ?: AutoReport.OPEN
    }

    fun set(context: Context, m: AutoReport) {
        mode = m
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}
