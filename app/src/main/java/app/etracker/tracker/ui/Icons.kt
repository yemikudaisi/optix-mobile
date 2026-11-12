package app.etracker.tracker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icons: the same stroked 24-unit drawings as the Situation page (frontend/src/situation/icons.js),
 * so the native tabs and the map's rail look like one set. Built from SVG path data; `Icon()` tints them.
 */
object AppIcons {
    private fun stroked(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Status: a pulse / heartbeat line. */
    val status: ImageVector by lazy { stroked("status", "M3 12h4l2-6 4 12 2-6h6") }

    /** Situation: a map with a position pin. */
    val map: ImageVector by lazy { stroked("map", "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z", "M9 4v14M15 6v14") }

    /** Maps: stacked map sheets (layers). */
    val layers: ImageVector by lazy { stroked("layers", "M12 3l9 5-9 5-9-5 9-5z", "M3 13l9 5 9-5M3 17.5l9 5 9-5") }

    /** Settings: a gear. */
    val settings: ImageVector by lazy {
        stroked(
            "settings",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1.08 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        )
    }

    /** Holder: a person. */
    val person: ImageVector by lazy { stroked("person", "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z") }

    /** Server / link. */
    val server: ImageVector by lazy { stroked("server", "M2 4h20v6H2zM2 14h20v6H2z", "M6 7h.01M6 17h.01") }

    /** Battery. */
    val battery: ImageVector by lazy { stroked("battery", "M2 7h17a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H2z", "M22 11v2", "M6 10v4M10 10v4") }
}
