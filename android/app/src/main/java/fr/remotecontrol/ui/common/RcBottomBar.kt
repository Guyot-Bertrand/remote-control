package fr.remotecontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors

enum class TopDestination(val label: String, val icon: ImageVector) {
    Home(Texts.navHome, RcIcons.Home),
    Browse(Texts.navBrowse, RcIcons.Browse),
    Search(Texts.navSearch, RcIcons.Search),
    Scan(Texts.navScan, RcIcons.Scan),
}

/** Barre de navigation principale (maquette) : pictogramme dans une pilule, libellé dessous. */
@Composable
fun RcBottomBar(selected: TopDestination, onSelect: (TopDestination) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(RcColors.surface)
            .navigationBarsPadding()
            .height(80.dp)
            .selectableGroup(),
    ) {
        TopDestination.entries.forEach { d ->
            val active = d == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(d) })
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) RcColors.accentSoft else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        d.icon,
                        contentDescription = null,
                        tint = if (active) RcColors.accentStrong else RcColors.text2,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    d.label,
                    style = TextStyle(
                        fontFamily = Inter,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (active) RcColors.text else RcColors.text2,
                    maxLines = 1,
                )
            }
        }
    }
}
