package fr.remotecontrol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.remotecontrol.ui.common.Texts

@Composable
fun SettingsScreen(
    state: SettingsUi,
    onManualInput: (String) -> Unit,
    onSaveManual: () -> Unit,
    onRediscover: () -> Unit,
    onResync: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(Texts.back) }
        Text(Texts.settingsServer)
        Text(state.serverLabel)
        OutlinedTextField(
            value = state.manualInput,
            onValueChange = onManualInput,
            label = { Text(Texts.manualAddress) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveManual, enabled = !state.busy) { Text(Texts.save) }
        OutlinedButton(onClick = onRediscover, enabled = !state.busy) { Text(Texts.rediscover) }
        OutlinedButton(onClick = onResync, enabled = !state.busy) { Text(Texts.resync) }
        Text(Texts.catalogInfo(state.revision, state.workCount))
        state.message?.let { Text(it) }
    }
}
