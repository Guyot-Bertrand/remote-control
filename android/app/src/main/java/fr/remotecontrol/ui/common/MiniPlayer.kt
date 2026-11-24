package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/** Lecture en cours sur la télé, résumée au-dessus de la barre de navigation. */
data class MiniPlayerUi(
    val title: String,
    val poster: String?,
    val subtitle: String,
    val progress: Float,
    val paused: Boolean,
    val workId: Long,
    val mediaFileId: Long,
)

/**
 * Rien sans lecture en cours (lecteur arrêté, lecture finie, ou cible inconnue). [info] : ce que le catalogue
 * local sait de la lecture (affiche, et titre si le serveur n'en donne pas).
 */
fun miniPlayerOf(s: PlayerSnapshotDto?, info: PlayingInfo? = null): MiniPlayerUi? {
    val st = s?.state ?: return null
    val current = s.current ?: return null
    if (!st.running || st.ended) return null
    val times = st.duration?.let { "${formatClock(st.position)} / ${formatClock(it)}" } ?: formatClock(st.position)
    return MiniPlayerUi(
        title = current.title ?: info?.title.orEmpty(),
        poster = info?.poster,
        subtitle = Texts.details(Texts.onTv, times),
        progress = st.duration?.takeIf { it > 0 }?.let { (st.position / it).coerceIn(0.0, 1.0).toFloat() } ?: 0f,
        paused = st.paused,
        workId = current.workId,
        mediaFileId = current.mediaFileId,
    )
}

/**
 * Bandeau « Sur la télé » des maquettes « Parcourir » et « Collection » (64 dp, surface 2) : affiche, titre,
 * temps écoulé et total, lecture/pause, fine barre d'avancement ; le toucher ouvre la télécommande.
 */
@Composable
fun MiniPlayer(ui: MiniPlayerUi, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(RcColors.surface2)
            .clickable(onClickLabel = Texts.remote, role = Role.Button, onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosterThumb(hash = ui.poster, title = ui.title, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.title,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = RcColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(ui.subtitle, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text2, maxLines = 1)
            }
            IconButton(onClick = onTogglePause) {
                Icon(
                    if (ui.paused) RcIcons.Play else RcIcons.Pause,
                    contentDescription = if (ui.paused) Texts.play else Texts.pause,
                    tint = RcColors.text,
                )
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(RcColors.accent.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(ui.progress).fillMaxHeight().background(RcColors.accent))
        }
    }
}
