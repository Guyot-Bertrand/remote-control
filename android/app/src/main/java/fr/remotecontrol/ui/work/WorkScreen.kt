package fr.remotecontrol.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.WorkCollectionRow
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.player.Availability
import fr.remotecontrol.player.SheetAction
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.ui.common.LanguagePill
import fr.remotecontrol.ui.common.MessageBanner
import fr.remotecontrol.ui.common.NoticeCard
import fr.remotecontrol.ui.common.NotFound
import fr.remotecontrol.ui.common.OpenRemoteAction
import fr.remotecontrol.ui.common.PlayerMissingBanner
import fr.remotecontrol.ui.common.Overline
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.QualityPill
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.SectionLabel
import fr.remotecontrol.ui.common.ShownOnTv
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.Tone
import fr.remotecontrol.ui.preplay.PrePlayLink
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

private const val LONG_TITLE = 24

/**
 * Maquettes « Fiche œuvre », « Fiche — non numérisé » et « Fiche — disque non branché » : affiche et
 * identité, boutons de lecture (ou encart d'indisponibilité), synopsis, casting, saisons, collections,
 * versions et mention « Affichée sur la télé ».
 * [onPrePlay] ouvre l'écran « Version et pistes… » (reprise si l'œuvre est entamée, sinon depuis le début).
 */
@Composable
fun WorkScreen(
    state: WorkUi?,
    missing: Boolean,
    server: ServerState,
    onPlayFromStart: () -> Unit,
    onResume: () -> Unit,
    onPrePlay: (StartMode) -> Unit,
    onOpenRemote: () -> Unit,
    onOpenSeason: (Long) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onDismissMessage: () -> Unit,
    onRetryServer: () -> Unit,
    onRetryPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = null, server = server, onBack = onBack)
        if (state == null) {
            if (missing) NotFound(Texts.workNotFound)
            return@Column
        }
        // Maquette « PC injoignable » : on parcourt la fiche, la lecture attend le retour du serveur.
        val offline = server == ServerState.NotFound
        if (offline) OfflineBanner(onRetryServer)
        if (state.playerMissing) PlayerMissingBanner(onRetryPlayer, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Identity(state)
            // Déjà en lecture sur la télé : on rouvre la télécommande au lieu de relancer (la télé ne clignote pas).
            if (state.action == SheetAction.OpenRemote) {
                OpenRemoteAction(onOpenRemote)
            } else if (!state.work.episodic) {
                PlayActions(state, offline, onPlayFromStart, onResume, onPrePlay)
            }
            state.message?.let { MessageBanner(it, onDismissMessage) }
            state.work.synopsis?.let { Synopsis(it) }
            if (state.work.episodic && state.seasons.isNotEmpty()) Seasons(state.seasons, onOpenSeason)
            if (state.work.cast.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(Texts.cast)
                    Text(state.work.cast.joinToString(", "), style = body14, color = RcColors.text2)
                }
            }
            if (state.collections.isNotEmpty()) Collections(state.collections, onOpenCollection)
            Versions(state)
        }
    }
}

private val body14 = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp)

@Composable
private fun Identity(state: WorkUi) {
    val work = state.work
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        PosterThumb(hash = work.poster, title = work.titleFr, year = work.year, modifier = Modifier.width(120.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(Texts.typeSingular(work.type))
            Text(
                work.titleFr,
                style = TextStyle(
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (work.titleFr.length > LONG_TITLE) 22.sp else 28.sp,
                    lineHeight = 1.12.em,
                ),
                color = RcColors.text,
                modifier = Modifier.semantics { heading() },
            )
            work.titleVo?.takeIf { it != work.titleFr }?.let {
                Text(it, style = TextStyle(fontFamily = Fraunces, fontStyle = FontStyle.Italic, fontSize = 16.sp), color = RcColors.text2)
            }
            work.titleAlt?.let { Text(Texts.alsoKnownAs(it), style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text3) }
            val details = Texts.details(work.year?.toString(), state.durationLabel)
            if (details.isNotEmpty()) Text(details, style = body14, color = RcColors.text2, modifier = Modifier.padding(top = 2.dp))
            if (work.directors.isNotEmpty()) {
                Text(
                    work.directors.joinToString(", "),
                    style = body14,
                    color = RcColors.text2,
                    modifier = Modifier.semantics { contentDescription = Texts.directorsDescription(work.directors) },
                )
            }
            if (work.genres.isNotEmpty()) {
                Text(Texts.genres(work.genres), style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = RcColors.text3)
            }
        }
    }
}

@Composable
private fun PlayActions(
    state: WorkUi,
    offline: Boolean,
    onPlayFromStart: () -> Unit,
    onResume: () -> Unit,
    onPrePlay: (StartMode) -> Unit,
) {
    val ready = !state.launching && !offline && state.action == SheetAction.Play
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(
            Texts.playFromStart, RcIcons.Play, onPlayFromStart, Modifier.fillMaxWidth(),
            enabled = state.canPlay && ready,
        )
        if (state.canPlay && state.resumeLabel != null) {
            PillButton(
                state.resumeLabel, RcIcons.Resume, onResume, Modifier.fillMaxWidth(),
                tone = Tone.Soft, enabled = ready, progress = state.resumeProgress,
            )
        }
        if (state.canPlay) {
            PrePlayLink(
                onClick = { onPrePlay(if (state.resumeLabel != null) StartMode.Resume else StartMode.Start) },
                enabled = ready,
                summary = state.choiceSummary,
            )
        }
        when (val a = state.availability) {
            is Availability.VolumeAbsent -> NoticeCard(
                icon = RcIcons.Disk,
                title = state.availabilityLabel ?: Texts.volumeAbsent(a.label),
                body = Texts.volumeAbsentHelp,
                warn = true,
                // Le serveur sait si le disque vient d'être branché : on retente la lecture.
                action = Texts.retry.takeIf { !state.launching && state.action == SheetAction.Play },
                onAction = onPlayFromStart,
            )
            Availability.NotDigitized -> state.medium?.let {
                NoticeCard(RcIcons.Disc, Texts.details(Texts.notDigitized, it), Texts.notDigitizedOnHelp(it), warn = false)
            } ?: NoticeCard(RcIcons.Disc, Texts.notDigitized, Texts.notDigitizedHelp, warn = false)
            Availability.Present, null -> Unit
        }
    }
}

@Composable
private fun Synopsis(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 23.sp),
        color = RcColors.text,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(
            onClickLabel = if (expanded) Texts.showLess else Texts.showMore,
            role = Role.Button,
        ) { expanded = !expanded },
    )
}

@Composable
private fun Seasons(seasons: List<SeasonEntity>, onOpenSeason: (Long) -> Unit) {
    Column {
        SectionLabel(Texts.seasons, Modifier.padding(bottom = 4.dp))
        seasons.forEach { season ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onOpenSeason(season.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(season.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp), color = RcColors.text, modifier = Modifier.weight(1f))
                Icon(RcIcons.ChevronRight, contentDescription = null, tint = RcColors.text3)
            }
        }
    }
}

@Composable
private fun Collections(collections: List<WorkCollectionRow>, onOpenCollection: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(Texts.sectionCollections)
        collections.forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { onOpenCollection(c.id) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Rang de l'œuvre dans la collection (« n° 2017 »), en laiton, comme la maquette.
                c.rank?.let {
                    Text(Texts.rank(it), style = body14.copy(fontWeight = FontWeight.SemiBold), color = RcColors.accent, maxLines = 1)
                }
                Text(c.name, style = body14, color = RcColors.text2)
            }
        }
    }
}

/** Bandeau « PC injoignable » (terre cuite), avec « Réessayer » : nouvelle recherche du serveur. */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RcColors.warnSoft)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RcIcons.Server, contentDescription = null, tint = RcColors.warn, modifier = Modifier.size(22.dp))
        Text(
            Texts.offlineNotice,
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
            color = RcColors.text,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onRetry, modifier = Modifier.height(40.dp)) {
            Text(Texts.retry, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = RcColors.accentStrong)
        }
    }
}

/**
 * Maquette 4c : section « Version » (une ligne par version : qualité et résolution, pastilles de langue) ou,
 * pour une œuvre non numérisée, « Support » (« DVD ») ; puis la mention « Affichée sur la télé ».
 */
@Composable
private fun Versions(state: WorkUi) {
    if (state.versions.isEmpty() && state.medium == null && !state.onTv) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.versions.isNotEmpty()) {
            SectionLabel(if (state.versions.size == 1) Texts.version else Texts.versions)
            state.versions.forEach { VersionPills(it) }
        } else if (state.medium != null) {
            SectionLabel(Texts.mediumSection)
            QualityPill(state.medium)
        }
        if (state.onTv) ShownOnTv(Modifier.align(Alignment.End))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionPills(v: VersionUi) {
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            if (v.absentLabel != null) contentDescription = Texts.versionDescription(v.label, v.absentLabel)
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        QualityPill(v.label, dimmed = !v.present, detail = v.resolution)
        v.languages.forEach { LanguagePill(it, dimmed = !v.present) }
    }
}
