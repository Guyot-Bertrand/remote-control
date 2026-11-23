package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

enum class TopDestination(val label: String) {
    Home(Texts.navHome),
    Browse(Texts.navBrowse),
    Search(Texts.navSearch),
    Scan(Texts.navScan),
}

/** Barre de navigation principale : libellés seuls (pas de bibliothèque d'icônes), pilule laiton sur l'actif. */
@Composable
fun RcBottomBar(selected: TopDestination, onSelect: (TopDestination) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(64.dp).background(RcColors.surface).selectableGroup()) {
        TopDestination.entries.forEach { d ->
            val active = d == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(d) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .height(32.dp)
                        .widthIn(min = 64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) RcColors.accentSoft else Color.Transparent)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        d.label,
                        style = TextStyle(
                            fontFamily = Inter,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) RcColors.accentStrong else RcColors.text2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
