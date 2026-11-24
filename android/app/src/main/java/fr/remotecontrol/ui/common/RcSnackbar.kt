package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

/** Message en bas d'écran des maquettes (ivoire, texte sombre), avec une action laiton (« OK », « Réessayer »). */
@Composable
fun RcSnackbar(text: String, onAction: () -> Unit, modifier: Modifier = Modifier, action: String = Texts.ok) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RcColors.text)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = RcColors.surface, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, modifier = Modifier.height(40.dp)) {
            Text(action, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = RcColors.accentStrong)
        }
    }
}
