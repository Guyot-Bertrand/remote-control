package fr.remotecontrol.ui.preplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import fr.remotecontrol.mirror.PublishTvView
import fr.remotecontrol.mirror.preplayView
import fr.remotecontrol.player.PlayTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.FilePillsRow
import fr.remotecontrol.ui.common.PlayerMissingBanner
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors
import java.util.Locale

/**
 * Maquette « Avant la lecture : choix des pistes » (plein écran) : en-tête (fermer, titre, pastille), affiche et
 * titre, puis versions (les absentes grisées, non sélectionnables), pistes audio, sous-titres (« Aucun »,
 * fichiers, intégrés), avec les badges « Par défaut » / « Dernier choix » ; bouton « Lire sur la télé » en bas.
 */
@Composable
fun PrePlayScreen(
    state: PrePlayUi,
    /** Œuvre ou épisode à lire, montré sur la télé avec les choix en cours. */
    target: PlayTarget,
    server: ServerState,
    /** Le lecteur n'est pas installé sur le PC : bandeau, et « Lire sur la télé » désactivé. */
    playerMissing: Boolean,
    onRetryPlayer: () -> Unit,
    onVersion: (Long) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (SubSel) -> Unit,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    PublishTvView(if (state.loading) null else preplayView(target, state.selectedMediaFileId, state.selectedAudio, state.selectedSubtitle))
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Texts.prePlayTitle, server, onBack = onClose, backIcon = RcIcons.Clear, backLabel = Texts.close)
        if (playerMissing) PlayerMissingBanner(onRetryPlayer, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Header(state)
            Section(Texts.version, RcIcons.Film) {
                state.versions.forEach { v ->
                    OptionRow(
                        label = v.label,
                        detail = Texts.details(v.resolution, v.absentLabel).ifEmpty { null },
                        badge = state.versionBadges[v.mediaFileId],
                        selected = v.mediaFileId == state.selectedMediaFileId,
                        enabled = v.present,
                        onClick = { onVersion(v.mediaFileId) },
                    )
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RcColors.accent, modifier = Modifier.size(32.dp))
                }
            } else if (state.subtitles.isNotEmpty()) {
                if (state.audio.isNotEmpty()) {
                    Divider()
                    Section(Texts.audio, RcIcons.Audio) {
                        state.audio.forEach { a ->
                            OptionRow(a.label, null, state.audioBadges[a.index], a.index == state.selectedAudio, true) { onAudio(a.index) }
                        }
                    }
                }
                Divider()
                Section(Texts.subtitles, RcIcons.Subtitles) {
                    state.subtitles.forEach { s ->
                        OptionRow(s.name, s.detail, state.subtitleBadges[s.sel], s.sel == state.selectedSubtitle, true) { onSubtitle(s.sel) }
                    }
                }
            }
            state.message?.let { Message(it, if (state.canRetry) onRetry else null) }
        }
        Column(
            Modifier.background(RcColors.bg).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(Texts.playOnTv, RcIcons.Play, onConfirm, Modifier.fillMaxWidth(), enabled = state.canConfirm && !playerMissing)
            Text(
                state.footer,
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                color = RcColors.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Affiche 40×60, titre (Fraunces 20) et détails (année · réalisation · durée, ou code de l'épisode). */
@Composable
private fun Header(state: PrePlayUi) {
    if (state.title.isEmpty()) return
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterThumb(hash = state.poster, title = state.title, year = state.year, modifier = Modifier.width(40.dp))
        Column {
            Text(
                state.title,
                style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 20.sp),
                color = RcColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.details?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = RcColors.text2) }
            state.pills?.let { FilePillsRow(it, Modifier.padding(top = 6.dp)) }
        }
    }
}

/** Légende (pictogramme et titre en capitales) puis un groupe de choix exclusifs. */
@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = RcColors.text3, modifier = Modifier.size(18.dp))
            Text(
                title.uppercase(Locale.FRENCH),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.12.em),
                color = RcColors.text3,
            )
        }
        content()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(RcColors.line))
}

/** Ligne de 56 dp : bouton radio laiton, libellé (gras si choisi), détail et badge éventuels. */
@Composable
private fun OptionRow(
    label: String,
    detail: String?,
    badge: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).border(2.dp, if (selected) RcColors.accent else RcColors.text2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(RcColors.accent))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = TextStyle(fontFamily = Inter, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp),
                color = RcColors.text,
            )
            detail?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text3) }
        }
        badge?.let {
            Text(
                it,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                color = RcColors.accentStrong,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(RcColors.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Carte d'erreur (fond terracotta doux), avec « Réessayer » quand [onRetry] est fourni. */
@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RcColors.warnSoft)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
            color = RcColors.text,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        onRetry?.let {
            Text(
                Texts.retry,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = RcColors.accentStrong,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = it)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

/** Lien « Version et pistes… » des fiches (48 dp, pictogramme sous-titres), qui ouvre l'écran de choix. */
@Composable
fun PrePlayLink(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, summary: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RcIcons.Subtitles, contentDescription = null, tint = RcColors.text2, modifier = Modifier.size(20.dp))
        if (summary == null) {
            Text(Texts.tracksAndVersion, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = RcColors.text2)
        } else {
            // Maquette « Fiche œuvre » : le choix qui sera lu, puis « Modifier » en laiton.
            Text(summary, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = RcColors.text2, modifier = Modifier.weight(1f))
            Text(Texts.change, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp), color = RcColors.accentStrong)
        }
    }
}
