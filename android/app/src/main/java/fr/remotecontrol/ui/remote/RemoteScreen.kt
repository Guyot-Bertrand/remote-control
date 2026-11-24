package fr.remotecontrol.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.ui.common.MessageBanner
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Fraunces
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

private const val VOLUME_STEP = 5

/**
 * Maquette « Télécommande » : en-tête « Sur la télé » (réduire, pastille), titre, barre de position
 * (écoulé, restant, total), gros boutons utilisables sans regarder (−10 s, lecture/pause, +10 s ; début,
 * −60 s, +60 s, arrêter), volume, puis audio et sous-titres en bas. Les commandes sont inactives sans lecture ;
 * en fin de lecture, l'écran propose de revenir à la fiche. L'écran reste allumé tant qu'il est affiché.
 */
@Composable
fun RemoteScreen(
    state: RemoteUi,
    server: ServerState,
    onTogglePause: () -> Unit,
    onRestart: () -> Unit,
    onSkip: (Int) -> Unit,
    onSeekDrag: (Double) -> Unit,
    onSeekRelease: () -> Unit,
    onVolumeDrag: (Int) -> Unit,
    onVolumeRelease: () -> Unit,
    onAudio: (Int?) -> Unit,
    onSubtitle: (SubSel) -> Unit,
    onStop: () -> Unit,
    onOpenSheet: () -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Texts.onTv, server, onBack = onBack, backIcon = RcIcons.ChevronDown, backLabel = Texts.minimize)
        state.message?.let { MessageBanner(it, onDismissMessage, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!state.running && state.finished) {
                    Finished(state, onOpenSheet)
                } else {
                    Column {
                        Title(state)
                        SeekBar(state, onSeekDrag, onSeekRelease)
                        Transport(state, onTogglePause, onRestart, onSkip, onStop)
                        Volume(state, onVolumeDrag, onVolumeRelease)
                    }
                    Tracks(state, onAudio, onSubtitle)
                }
            }
        }
    }
}

/** Garde l'écran allumé tant que la télécommande est affichée (équivalent de FLAG_KEEP_SCREEN_ON). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun Title(state: RemoteUi) {
    // Maquette : affiche 56×84 à gauche, titre (Fraunces 30) et « 2017 · FHD · Français » à droite.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.running || state.loading) {
            PosterThumb(hash = state.poster, title = state.title, modifier = Modifier.width(56.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.title,
                style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 33.sp),
                color = RcColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            val line = Texts.details(state.details.ifEmpty { null }, Texts.pausedNow.takeIf { state.running && state.paused })
            if (line.isNotEmpty()) {
                Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = RcColors.text2)
            }
        }
    }
}

private val clockStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontFeatureSettings = "tnum")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: RemoteUi, onSeekDrag: (Double) -> Unit, onSeekRelease: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val duration = state.duration?.takeIf { it > 0 }
    Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(
            value = state.position.coerceIn(0.0, duration ?: 0.0).toFloat(),
            onValueChange = { onSeekDrag(it.toDouble()) },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSeekRelease()
            },
            enabled = state.running && duration != null,
            valueRange = 0f..(duration ?: 1.0).toFloat(),
            thumb = { Thumb(state.running) },
            track = { Track(it, RcColors.accent) },
            modifier = Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = Texts.seekPosition },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.positionLabel, style = clockStyle.copy(fontWeight = FontWeight.SemiBold), color = RcColors.text)
            Text(state.remainingLabel, style = clockStyle, color = RcColors.text2)
            Text(state.durationLabel, style = clockStyle, color = RcColors.text2)
        }
    }
}

/** Pouce de la barre de position : 28 dp laiton, halo de 6 dp. */
@Composable
private fun Thumb(enabled: Boolean) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) RcColors.accent.copy(alpha = 0.2f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (enabled) RcColors.accentStrong else RcColors.surface3))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Track(state: SliderState, fill: Color) {
    val span = state.valueRange.endInclusive - state.valueRange.start
    val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(RcColors.surface3),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill))
    }
}

@Composable
private fun Transport(
    state: RemoteUi,
    onTogglePause: () -> Unit,
    onRestart: () -> Unit,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val enabled = state.running
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(76.dp, Texts.skipDescription(-10), enabled, { onSkip(-10) }, RcIcons.Rewind, Texts.skipLabel(-10))
        RoundButton(
            size = 112.dp,
            description = if (state.paused) Texts.play else Texts.pause,
            enabled = enabled,
            onClick = onTogglePause,
            icon = if (state.paused) RcIcons.Play else RcIcons.Pause,
            background = RcColors.accent,
            content = RcColors.onAccent,
        )
        RoundButton(76.dp, Texts.skipDescription(10), enabled, { onSkip(10) }, RcIcons.Forward, Texts.skipLabel(10))
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(64.dp, Texts.restart, enabled, onRestart, RcIcons.FromStart)
        RoundButton(64.dp, Texts.skipDescription(-60), enabled, { onSkip(-60) }, label = Texts.skipLabel(-60))
        RoundButton(64.dp, Texts.skipDescription(60), enabled, { onSkip(60) }, label = Texts.skipLabel(60))
        RoundButton(64.dp, Texts.stop, enabled, onStop, RcIcons.Stop, content = RcColors.warn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Volume(state: RemoteUi, onVolumeDrag: (Int) -> Unit, onVolumeRelease: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val enabled = state.running
    fun step(delta: Int) {
        onVolumeDrag((state.volume + delta).coerceIn(0, 100))
        onVolumeRelease()
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(56.dp, Texts.volumeDown, enabled, { step(-VOLUME_STEP) }, RcIcons.VolumeDown)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Slider(
                value = state.volume.coerceIn(0, 100).toFloat(),
                onValueChange = { onVolumeDrag(it.toInt()) },
                onValueChangeFinished = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onVolumeRelease()
                },
                enabled = enabled,
                valueRange = 0f..100f,
                thumb = {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(if (enabled) RcColors.text else RcColors.surface3))
                },
                track = { Track(it, RcColors.text2) },
                modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = Texts.volume },
            )
            Text(Texts.volumeLevel(state.volume), style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text3)
        }
        RoundButton(56.dp, Texts.volumeUp, enabled, { step(VOLUME_STEP) }, RcIcons.VolumeUp)
    }
}

/** Bouton rond des maquettes : icône et/ou libellé (15/600), vibration à l'appui. */
@Composable
private fun RoundButton(
    size: Dp,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    label: String? = null,
    background: Color = RcColors.surface2,
    content: Color = RcColors.text,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.36f + 0.5.dp))
        if (label != null) {
            Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = content, maxLines = 1)
        }
    }
}

@Composable
private fun Tracks(state: RemoteUi, onAudio: (Int?) -> Unit, onSubtitle: (SubSel) -> Unit) {
    val audioLabel = state.audio.firstOrNull { it.index == state.selectedAudio }?.label ?: Texts.unknownTrack
    val subtitleLabel = state.subtitles.firstOrNull { it.sel == state.selectedSubtitle }?.label ?: Texts.subtitleNone
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TrackChip(
            title = Texts.audio,
            value = audioLabel,
            icon = RcIcons.Audio,
            enabled = state.running && state.audio.isNotEmpty(),
            options = state.audio.map { it.label to (it.index == state.selectedAudio) },
            onPick = { onAudio(state.audio[it].index) },
            modifier = Modifier.weight(1f),
        )
        TrackChip(
            title = Texts.subtitles,
            value = subtitleLabel,
            icon = RcIcons.Subtitles,
            enabled = state.running && state.subtitles.isNotEmpty(),
            options = state.subtitles.map { it.label to (it.sel == state.selectedSubtitle) },
            onPick = { onSubtitle(state.subtitles[it].sel) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Puce « Audio » / « Sous-titres » : valeur actuelle, et menu des choix au toucher. */
@Composable
private fun TrackChip(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    options: List<Pair<String, Boolean>>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(RoundedCornerShape(16.dp))
                .background(RcColors.surface2)
                .clickable(enabled = enabled, role = Role.Button) { open = true }
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = RcColors.text, modifier = Modifier.size(22.dp))
            Column {
                Text(title, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = RcColors.text3)
                Text(
                    value,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = RcColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = RcColors.surface2) {
            options.forEachIndexed { i, (label, selected) ->
                DropdownMenuItem(
                    text = { Text(label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp), color = RcColors.text) },
                    trailingIcon = if (selected) {
                        { Icon(RcIcons.Check, contentDescription = null, tint = RcColors.accentStrong) }
                    } else {
                        null
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        open = false
                        onPick(i)
                    },
                )
            }
        }
    }
}

/** Fin de lecture : « Lecture terminée » (ou interrompue), titre, et retour à la fiche. */
@Composable
private fun Finished(state: RemoteUi, onOpenSheet: () -> Unit) {
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (state.interrupted) Texts.playbackInterrupted else Texts.playbackEnded,
            style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 33.sp),
            color = RcColors.text,
            modifier = Modifier.semantics { heading() },
        )
        state.finishedTitle?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 15.sp), color = RcColors.text2) }
    }
    if (state.workId != null) {
        PillButton(Texts.backToSheet, RcIcons.Back, onOpenSheet, Modifier.fillMaxWidth().padding(top = 24.dp))
    }
}
