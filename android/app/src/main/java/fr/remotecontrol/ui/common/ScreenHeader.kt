package fr.remotecontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/**
 * En-tête d'écran des maquettes (64 dp) : retour facultatif (ou fermeture, avec [backIcon] et [backLabel]),
 * titre facultatif, pastille de connexion (sauf [showChip] faux : les réglages montrent l'état en détail).
 */
@Composable
fun ScreenHeader(
    title: String?,
    server: ServerState,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = RcIcons.Back,
    backLabel: String = Texts.back,
    showChip: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(backIcon, contentDescription = backLabel, tint = RcColors.text)
            }
        }
        if (title != null) {
            Text(
                title,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp),
                color = RcColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack == null) 12.dp else 0.dp)
                    .semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showChip) ConnectionChip(server, Modifier.padding(end = 4.dp))
    }
}
