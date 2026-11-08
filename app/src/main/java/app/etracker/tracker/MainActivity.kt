package app.etracker.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.etracker.tracker.ui.TrackerApp
import app.etracker.tracker.ui.TrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrackerTheme { TrackerApp() } }
    }
}
