package fr.remotecontrol.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.PillButton
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.RcSnackbar
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.Tone
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/**
 * Maquette « Réglages » : sections SERVEUR (serveur et son état, saisie manuelle de l'adresse, nouvelle
 * recherche), CATALOGUE (dernière synchronisation, « Resynchroniser ») et À PROPOS (version de l'appli) ;
 * les messages s'affichent en bas d'écran, avec « Réessayer » après une synchronisation en échec.
 */
@Composable
fun SettingsScreen(
    state: SettingsUi,
    onManualInput: (String) -> Unit,
    onSaveManual: () -> Unit,
    onRediscover: () -> Unit,
    onResync: () -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(RcColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(Texts.settings, state.server, onBack = onBack, showChip = false)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
                SectionTitle(Texts.sectionServer)
                ServerRow(state.server)
                LinkRow(
                    RcIcons.Edit,
                    Texts.enterAddress,
                    trailing = if (editing) RcIcons.ChevronDown else RcIcons.ChevronRight,
                ) { editing = !editing }
                if (editing) ManualAddress(state, onManualInput, onSaveManual)
                LinkRow(RcIcons.Search, Texts.rediscover, enabled = !state.busy, onClick = onRediscover)

                SectionTitle(Texts.sectionCatalog)
                InfoRow(
                    RcIcons.Sync,
                    Texts.lastSyncTitle,
                    Texts.lastSync(state.syncedAt, state.now, state.workCount, state.playableCount),
                )
                Box(Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp)) {
                    PillButton(Texts.resync, RcIcons.Sync, onResync, tone = Tone.Soft, enabled = !state.busy, height = 40.dp)
                }

                SectionTitle(Texts.sectionAbout)
                InfoRow(RcIcons.Remote, Texts.appVersion, Texts.appVersionLabel(appVersion()))
            }
        }
        state.message?.let { message ->
            RcSnackbar(
                text = message,
                action = if (state.syncFailed) Texts.retry else Texts.ok,
                onAction = if (state.syncFailed) onResync else onDismissMessage,
                // Après un échec : « Réessayer », mais aussi « OK » pour simplement refermer.
                dismiss = onDismissMessage.takeIf { state.syncFailed },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
}

private val title16 = TextStyle(fontFamily = Inter, fontSize = 16.sp)
private val caption13 = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.12.em),
        color = RcColors.accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Ligne de 72 dp : pictogramme, titre et détail, élément facultatif à droite. */
@Composable
private fun InfoRow(icon: ImageVector, title: String, detail: String?, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = RcColors.text2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = title16, color = RcColors.text)
            detail?.let { Text(it, style = caption13, color = RcColors.text2) }
        }
        trailing()
    }
}

@Composable
private fun ServerRow(server: ServerState) {
    val (title, detail) = when (server) {
        is ServerState.Connected -> server.address.display to (if (server.manual) Texts.enteredByHand else Texts.detectedAuto)
        ServerState.Searching -> Texts.searching to null
        ServerState.NotFound -> Texts.noServerYet to Texts.noServer
    }
    val (status, color) = when (server) {
        is ServerState.Connected -> Texts.statusConnected to RcColors.seen
        ServerState.Searching -> Texts.chipSearching to RcColors.accent
        ServerState.NotFound -> Texts.chipOffline to RcColors.warn
    }
    InfoRow(RcIcons.Server, title, detail) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(status, style = caption13, color = color)
        }
    }
}

/** Ligne de 56 dp menant à une action (chevron à droite). */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    trailing: ImageVector = RcIcons.ChevronRight,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = RcColors.text2)
        Text(title, style = title16, color = if (enabled) RcColors.text else RcColors.text3, modifier = Modifier.weight(1f))
        Icon(trailing, contentDescription = null, tint = RcColors.text3, modifier = Modifier.size(20.dp))
    }
}

/** Saisie de l'adresse (« hôte » ou « hôte:port »), dans le champ arrondi des maquettes, puis « Enregistrer ». */
@Composable
private fun ManualAddress(state: SettingsUi, onManualInput: (String) -> Unit, onSave: () -> Unit) {
    Column(
        Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(RcColors.surface2)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val style = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = RcColors.text)
            if (state.manualInput.isEmpty()) Text(Texts.addressHint, style = style.copy(color = RcColors.text3))
            BasicTextField(
                value = state.manualInput,
                onValueChange = onManualInput,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(RcColors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = Texts.manualAddress },
            )
        }
        PillButton(Texts.save, RcIcons.Check, onSave, tone = Tone.Primary, enabled = !state.busy, height = 40.dp)
    }
}
