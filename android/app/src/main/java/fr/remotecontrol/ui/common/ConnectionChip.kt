package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.theme.RcColors

/** Pastille d'état du serveur : sauge si connecté, laiton en recherche, terre cuite hors ligne. */
@Composable
fun ConnectionChip(server: ServerState, modifier: Modifier = Modifier) {
    val (label, dot) = when (server) {
        is ServerState.Connected -> Texts.chipConnected to RcColors.seen
        ServerState.Searching -> Texts.chipSearching to RcColors.accent
        ServerState.NotFound -> Texts.chipOffline to RcColors.warn
    }
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RcColors.surface2)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (server == ServerState.NotFound) RcColors.warn else RcColors.text2,
            maxLines = 1,
        )
    }
}
