package fr.remotecontrol.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icônes au trait des maquettes (grille 24, trait 2, bouts arrondis), recopiées de leurs SVG. */
object RcIcons {
    val Home = icon("Home", "M3.5 10.5 12 3.5l8.5 7", "M5.5 9v11h13V9")
    val Browse = icon("Browse", rect(3.5f, 3.5f), rect(13.5f, 3.5f), rect(3.5f, 13.5f), rect(13.5f, 13.5f))
    val Search = icon("Search", circle(11f, 11f, 7f), "m20 20-3.6-3.6")
    val Scan = icon(
        "Scan",
        "M4 8.5V5a1 1 0 0 1 1-1h3.5M15.5 4H19a1 1 0 0 1 1 1v3.5M20 15.5V19a1 1 0 0 1-1 1h-3.5M8.5 20H5a1 1 0 0 1-1-1v-3.5",
        "M7.5 12h9",
    )
    val Settings = icon(
        "Settings",
        "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1",
        circle(15f, 6f, 2f), circle(9f, 12f, 2f), circle(17f, 18f, 2f),
    )
    val Collection = icon("Collection", "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01")
    val ChevronRight = icon("ChevronRight", "m9 5 7 7-7 7")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    /** Carré de 7 au rayon 1,5, comme le pictogramme « Parcourir ». */
    private fun rect(x: Float, y: Float, s: Float = 7f, r: Float = 1.5f) =
        "M${x + r} ${y}h${s - 2 * r}a$r $r 0 0 1 $r ${r}v${s - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(s - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(s - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
