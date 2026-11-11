package app.etracker.tracker.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** The user's choice in the Status tab. AUTO follows the Android setting. */
enum class ThemeMode(val label: String, val js: String) {
    AUTO("Auto", "auto"), LIGHT("Light", "light"), DARK("Dark", "dark");
}

/** Persisted theme override. Not sensitive, so plain SharedPreferences (survives sign-out). */
object ThemePrefs {
    private const val FILE = "ui_prefs"
    private const val KEY = "theme"

    var mode by mutableStateOf(ThemeMode.AUTO)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        mode = ThemeMode.values().firstOrNull { it.name == saved } ?: ThemeMode.AUTO
    }

    fun set(context: Context, m: ThemeMode) {
        mode = m
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}

// Palette. Body text pairs are all >= 4.5:1 (see README, "Themes").
private val Slate = Color(0xFF0F172A)   // dark background
private val SlateCard = Color(0xFF1E293B)
private val Teal = Color(0xFF0E7490)    // light-theme primary (5.4:1 on white)
private val Cyan = Color(0xFF22D3EE)    // dark-theme primary

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    secondary = Color(0xFFB45309), onSecondary = Color.White, // amber, readable as text on light
    tertiary = Color(0xFF15803D), onTertiary = Color.White,
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0), onSurfaceVariant = Color(0xFF475569),
    surfaceContainer = Color(0xFFEFF3F8), surfaceContainerHigh = Color(0xFFE8EDF4),
    outline = Color(0xFF64748B), outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Cyan, onPrimary = Slate,
    secondary = Color(0xFFFBBF24), onSecondary = Slate,
    tertiary = Color(0xFF4ADE80), onTertiary = Slate,
    background = Slate, onBackground = Color(0xFFE5E7EB),
    surface = Slate, onSurface = Color(0xFFE5E7EB),
    surfaceVariant = SlateCard, onSurfaceVariant = Color(0xFFA8B3C4),
    surfaceContainer = SlateCard, surfaceContainerHigh = Color(0xFF273449),
    outline = Color(0xFF64748B), outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171), onError = Slate,
)

/** The effective dark/light state: the override, else the OS. */
@Composable
fun isDarkNow(): Boolean = when (ThemePrefs.mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.AUTO -> isSystemInDarkTheme()
}

/** The Situation page's background in the given mode (matches --bg in situation.css), as ARGB. */
fun webBackground(dark: Boolean): Int = (if (dark) Slate else Color(0xFFE5E7EB)).toArgb()

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    val dark = isDarkNow()
    val activity = LocalContext.current as? ComponentActivity
    // status / navigation bars: transparent, with icons that contrast with the theme
    DisposableEffect(dark) {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
