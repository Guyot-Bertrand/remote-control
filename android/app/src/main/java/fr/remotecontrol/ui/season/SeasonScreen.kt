package fr.remotecontrol.ui.season

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.ui.common.MessageBanner
import fr.remotecontrol.ui.common.NotFound
import fr.remotecontrol.ui.common.Overline
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.Tone
import fr.remotecontrol.ui.preplay.PrePlayLink
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/** Clés des éléments placés avant les épisodes ; l'index d'un épisode s'en déduit. */
private val HEADER_KEYS = listOf("header", "selector", "summary", "message")

/**
 * Maquette « Fiche saison » : en-tête de la série, sélecteur de saison, puis les épisodes ; l'épisode mis en
 * évidence (carte laiton, « Reprendre » / « Depuis le début ») est amené à l'écran à l'ouverture.
 * [onPrePlay] ouvre l'écran « Version et pistes… » pour l'épisode mis en évidence.
 */
@Composable
fun SeasonScreen(
    state: SeasonUi?,
    missing: Boolean,
    server: ServerState,
    onPlay: (Long, StartMode) -> Unit,
    onPrePlay: (Long, StartMode) -> Unit,
    onOpenSeason: (Long) -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    val list = rememberLazyListState()
    val headerVisible by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = state?.let { seasonBarTitle(it.workTitle, headerVisible) }, server = server, onBack = onBack)
        if (state == null) {
            if (missing) NotFound(Texts.seasonNotFound)
            return@Column
        }
        var scrolled by rememberSaveable(state.season.id) { mutableStateOf(false) }
        val highlight = state.episodes.indexOfFirst { it.highlighted }
        LaunchedEffect(state.season.id, highlight) {
            if (!scrolled && highlight >= 0) {
                list.scrollToItem(HEADER_KEYS.size + highlight)
                scrolled = true
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(HEADER_KEYS, key = { it }) { key ->
                when (key) {
                    "header" -> SeriesHeader(state)
                    "selector" -> SeasonSelector(state.seasons, state.season.id, onOpenSeason)
                    "summary" -> Summary(state)
                    "message" -> state.message?.let {
                        MessageBanner(it, onDismissMessage, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            items(state.episodes, key = { it.id }) { episode ->
                Box(Modifier.padding(horizontal = 8.dp)) {
                    val enabled = episode.playable && !state.launching
                    if (episode.highlighted) HighlightedEpisode(episode, enabled, onPlay, onPrePlay) else EpisodeLine(episode, enabled, onPlay)
                }
            }
        }
    }
}

/** Titre de la barre : celui de la série dès que son en-tête n'est plus à l'écran (ouverture sur l'épisode en cours). */
internal fun seasonBarTitle(workTitle: String, headerVisible: Boolean): String? =
    workTitle.takeIf { !headerVisible && it.isNotBlank() }

@Composable
private fun SeriesHeader(state: SeasonUi) {
    val work = state.work
    Column {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PosterThumb(hash = work?.poster, title = state.workTitle, year = work?.year, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                work?.let { Overline(Texts.typeSingular(it.type)) }
                Text(
                    state.workTitle,
                    style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 1.1.em),
                    color = RcColors.text,
                    modifier = Modifier.semantics { heading() },
                )
                val details = Texts.details(
                    work?.year?.toString(),
                    work?.genres?.firstOrNull()?.let { Texts.genres(listOf(it)) },
                    Texts.seasonCount(state.seasons.size).takeIf { state.seasons.isNotEmpty() },
                )
                if (details.isNotEmpty()) Text(details, style = caption13, color = RcColors.text2)
                work?.directors?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(", "), style = caption13, color = RcColors.text2)
                }
            }
        }
        work?.synopsis?.let {
            Text(
                it,
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp),
                color = RcColors.text2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
        }
    }
}

private val caption13 = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp)

/** Sélecteur segmenté des saisons de la série ; la saison affichée est cochée. */
@Composable
private fun SeasonSelector(seasons: List<SeasonEntity>, current: Long, onOpenSeason: (Long) -> Unit) {
    if (seasons.size < 2) return
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(1.dp, RcColors.text3, shape)
            .semantics { contentDescription = Texts.season },
    ) {
        seasons.forEachIndexed { i, season ->
            val selected = season.id == current
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(RcColors.text3))
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) RcColors.accentSoft else Color.Transparent)
                    .clickable(enabled = !selected, role = Role.Tab) { onOpenSeason(season.id) }
                    .semantics { this.selected = selected },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) Icon(RcIcons.Check, contentDescription = null, tint = RcColors.accentStrong, modifier = Modifier.size(18.dp))
                Text(
                    season.label,
                    style = TextStyle(fontFamily = Inter, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp),
                    color = if (selected) RcColors.accentStrong else RcColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Summary(state: SeasonUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            Texts.episodesSummary(state.episodes.size, state.episodes.count { it.watched }),
            style = caption13,
            color = RcColors.text3,
            modifier = Modifier.weight(1f),
        )
        if (state.onTv) Text(Texts.shownOnTv, style = caption13, color = RcColors.text3)
    }
}

/** Ligne d'épisode (64 dp, filet bas) : vu (coche), « Reprendre » si entamé, « Depuis le début ». */
@Composable
private fun EpisodeLine(episode: EpisodeUi, enabled: Boolean, onPlay: (Long, StartMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(RcColors.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(start = 16.dp, end = 0.dp)
            .alpha(if (episode.playable) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.label,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                color = if (episode.watched) RcColors.text2 else RcColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.status?.let { Text(it, style = caption13, color = RcColors.text3) }
        }
        if (episode.watched) {
            // Décorative : le statut « Vu » est déjà lu.
            Icon(RcIcons.Check, contentDescription = null, tint = RcColors.seen, modifier = Modifier.size(22.dp))
        }
        if (episode.canResume) {
            IconButton(onClick = { onPlay(episode.id, StartMode.Resume) }, enabled = enabled) {
                Icon(RcIcons.Resume, contentDescription = Texts.episodeAction(Texts.resume, episode.label), tint = RcColors.accentStrong)
            }
        }
        IconButton(onClick = { onPlay(episode.id, StartMode.Start) }, enabled = enabled) {
            Icon(RcIcons.Play, contentDescription = Texts.episodeAction(Texts.fromStart, episode.label), tint = RcColors.text)
        }
    }
}

/** Épisode mis en évidence : carte laiton doux, badge « En cours », barre d'avancement et boutons. */
@Composable
private fun HighlightedEpisode(
    episode: EpisodeUi,
    enabled: Boolean,
    onPlay: (Long, StartMode) -> Unit,
    onPrePlay: (Long, StartMode) -> Unit,
) {
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RcColors.accentSoft)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    episode.label,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = RcColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.status?.let { Text(it, style = caption13, color = RcColors.text2) }
            }
            if (episode.canResume) {
                Text(
                    Texts.inProgress,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    color = RcColors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(RcColors.accent).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        episode.progress?.let { p ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(RcColors.accent.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(RcColors.accent))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (episode.canResume) {
                // « Depuis le début » garde sa largeur naturelle (jamais tronqué) ; « Reprendre » prend le reste.
                PillButton(
                    Texts.resume, RcIcons.Play, { onPlay(episode.id, StartMode.Resume) },
                    Modifier.weight(1f), enabled = enabled, height = 48.dp,
                )
                PillButton(
                    Texts.fromStart, RcIcons.FromStart, { onPlay(episode.id, StartMode.Start) },
                    tone = Tone.Outlined, enabled = enabled, height = 48.dp,
                )
            } else {
                PillButton(
                    Texts.fromStart, RcIcons.Play, { onPlay(episode.id, StartMode.Start) },
                    Modifier.fillMaxWidth(), enabled = enabled, height = 48.dp,
                )
            }
        }
        PrePlayLink(
            onClick = { onPrePlay(episode.id, if (episode.canResume) StartMode.Resume else StartMode.Start) },
            enabled = enabled,
        )
    }
}
