package fr.remotecontrol.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/**
 * Vignette d'affiche 2:3 et ses états (maquette « Composants récurrents ») :
 * entamée (barre laiton), vue (pastille cochée), indisponible (voile et libellé),
 * sans affiche (titre en Fraunces sur fond uni, filet laiton).
 */
@Composable
fun PosterThumb(
    hash: String?,
    title: String,
    modifier: Modifier = Modifier,
    year: Int? = null,
    progress: Float? = null,
    watched: Boolean = false,
    unavailableLabel: String? = null,
) {
    BoxWithConstraints(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = title },
    ) {
        val width = maxWidth
        if (hash == null) NoPoster(title, year, width) else Poster(hash, AssetSize.Thumb, Modifier.fillMaxSize())
        if (unavailableLabel != null) {
            Box(Modifier.fillMaxSize().background(RcColors.scrim(0.62f)), contentAlignment = Alignment.Center) {
                Text(
                    unavailableLabel,
                    style = TextStyle(
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.06.em,
                    ),
                    color = RcColors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(RcColors.scrim(0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (watched) {
            SeenBadge(
                Modifier.align(Alignment.TopEnd).padding(width * 0.05f).size(width * 0.21f)
                    .semantics { contentDescription = Texts.watched },
            )
        }
        if (progress != null) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(RcColors.scrim(0.8f)),
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(RcColors.accent))
            }
        }
    }
}

@Composable
private fun NoPoster(title: String, year: Int?, width: Dp) {
    val inset = width * 0.06f
    Box(Modifier.fillMaxSize().background(RcColors.surface2).padding(inset)) {
        Column(
            Modifier
                .fillMaxSize()
                .border(1.dp, RcColors.accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(inset),
            verticalArrangement = Arrangement.spacedBy(inset, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(width * 0.15f).height(2.dp).background(RcColors.accent))
            Text(
                title,
                style = TextStyle(
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.Medium,
                    fontSize = (width.value * 0.115f).sp,
                    lineHeight = 1.12.em,
                ),
                color = RcColors.text,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (year != null) {
                Text(
                    year.toString(),
                    style = TextStyle(fontFamily = Inter, fontSize = 10.sp, letterSpacing = 0.08.em),
                    color = RcColors.text2,
                )
            }
        }
    }
}

@Composable
private fun SeenBadge(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawCircle(RcColors.scrim(0.88f))
        drawCircle(RcColors.seen, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        val w = size.width
        val check = Path().apply {
            moveTo(w * 0.30f, w * 0.52f)
            lineTo(w * 0.44f, w * 0.66f)
            lineTo(w * 0.71f, w * 0.37f)
        }
        drawPath(check, RcColors.seen, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

