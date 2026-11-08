package app.etracker.tracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Gold = Color(0xFFE0A100)
private val Slate = Color(0xFF0F172A)

private val Light = lightColorScheme(primary = Color(0xFF0E7490), secondary = Gold, onPrimary = Color.White)
private val Dark = darkColorScheme(primary = Color(0xFF22D3EE), secondary = Gold, background = Slate, surface = Color(0xFF1E293B))

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
