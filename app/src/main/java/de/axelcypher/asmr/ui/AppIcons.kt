package de.axelcypher.asmr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Die wenigen Material-Icons, die die App braucht (Pfade aus den Material Icons, Apache 2.0).
 * Bewusst ohne `material-icons-extended`: die Bibliothek ist riesig und wir nutzen ein Dutzend Icons.
 */
object AppIcons {
    val Play by icon("M8,5v14l11,-7z")
    val Pause by icon("M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val SkipNext by icon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val SkipPrevious by icon("M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z")
    val Replay by icon(
        "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
    )
    val Forward by icon(
        "M18,13c0,3.31 -2.69,6 -6,6s-6,-2.69 -6,-6 2.69,-6 6,-6v4l5,-5 -5,-5v4c-4.42,0 -8,3.58 -8,8s3.58,8 8,8 8,-3.58 8,-8h-2z",
    )
    val Shuffle by icon(
        "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5L20,4h-5.5z" +
            "M14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20L20,20v-5.5l-2.04,2.04 -3.13,-3.13z",
    )
    val Repeat by icon("M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")
    val RepeatOne by icon(
        "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15L13,9h-1l-2,1v1h1.5v4L13,15z",
    )
    val Timer by icon(
        "M15,1L9,1v2h6L15,1zM11,14h2L13,8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
            "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
            "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
    val Bedtime by icon(
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02 -7.51,-0.25 -12.09,-8.43 -8.32,-14.96z",
    )
    val ArrowDown by icon("M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    val ArrowBack by icon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val MoreVert by icon(
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2" +
            " -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    )
    val Add by icon("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Folder by icon("M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Lock by icon(
        "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
            "c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
            "M15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
    )

    private fun icon(pathData: String) = lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
    }
}
