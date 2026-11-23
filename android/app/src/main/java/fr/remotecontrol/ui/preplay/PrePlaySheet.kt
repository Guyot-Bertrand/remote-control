package fr.remotecontrol.ui.preplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors
import java.util.Locale

/**
 * Maquette « Avant la lecture : choix des pistes » : versions (les absentes grisées, non sélectionnables),
 * pistes audio, sous-titres (« Aucun », fichiers, intégrés), puis le bouton de lancement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrePlaySheet(
    state: PrePlayUi,
    onVersion: (Long) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (SubSel) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RcColors.surface,
        contentColor = RcColors.text,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                Texts.prePlayTitle,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp),
                color = RcColors.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
            )
            Section(Texts.version, RcIcons.Film) {
                state.versions.forEach { v ->
                    OptionRow(
                        label = v.label,
                        detail = v.absentLabel,
                        selected = v.mediaFileId == state.selectedMediaFileId,
                        enabled = v.present,
                        onClick = { onVersion(v.mediaFileId) },
                    )
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RcColors.accent, modifier = Modifier.size(32.dp))
                }
                state.subtitles.isNotEmpty() -> {
                    if (state.audio.isNotEmpty()) {
                        Divider()
                        Section(Texts.audio, RcIcons.Audio) {
                            state.audio.forEach { a ->
                                OptionRow(a.label, null, a.index == state.selectedAudio, true) { onAudio(a.index) }
                            }
                        }
                    }
                    Divider()
                    Section(Texts.subtitles, RcIcons.Subtitles) {
                        state.subtitles.forEach { s ->
                            OptionRow(s.label, null, s.sel == state.selectedSubtitle, true) { onSubtitle(s.sel) }
                        }
                    }
                }
            }
            state.message?.let {
                Text(
                    it,
                    style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
                    color = RcColors.text,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RcColors.warnSoft)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(Texts.startPlayback, RcIcons.Play, onConfirm, Modifier.fillMaxWidth(), enabled = state.canConfirm)
                Text(
                    Texts.choicesRemembered,
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                    color = RcColors.text3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/** Ligne de 56 dp : bouton radio laiton, libellé (gras si choisi), détail éventuel. */
@Composable
private fun OptionRow(label: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
    }
}

/** Lien « Version et pistes… » des fiches (48 dp, pictogramme sous-titres), qui ouvre la feuille. */
@Composable
fun PrePlayLink(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
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
        Text(Texts.tracksAndVersion, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = RcColors.text2)
    }
}
