package fr.remotecontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.remotecontrol.ui.theme.RcColors

/**
 * Bascule « Tout le catalogue » des maquettes : activée, elle montre aussi les œuvres non lisibles
 * (donc l'inverse de [playableOnly]).
 */
@Composable
fun CatalogueSwitch(playableOnly: Boolean, onPlayableOnly: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .toggleable(value = !playableOnly, role = Role.Switch, onValueChange = { all -> onPlayableOnly(!all) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            Texts.wholeCatalogue,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
            color = RcColors.text2,
        )
        Switch(
            checked = !playableOnly,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RcColors.onAccent,
                checkedTrackColor = RcColors.accent,
                checkedBorderColor = RcColors.accent,
                uncheckedThumbColor = RcColors.text3,
                uncheckedTrackColor = RcColors.surface3,
                uncheckedBorderColor = RcColors.text3,
            ),
        )
    }
}
