package app.etracker.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.etracker.tracker.data.DeviceReset
import app.etracker.tracker.data.Updater
import app.etracker.tracker.ui.TrackerApp
import app.etracker.tracker.ui.TrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // status-bar icons follow light/dark so the clock is readable
        DeviceReset.resume(this) // finish an interrupted wipe / resend its acknowledgement
        setContent { TrackerTheme { TrackerApp() } }
    }

    override fun onResume() {
        super.onResume()
        // back from the "install unknown apps" settings screen? carry on with the update
        Thread { Updater.onResume(applicationContext) }.start()
    }
}
