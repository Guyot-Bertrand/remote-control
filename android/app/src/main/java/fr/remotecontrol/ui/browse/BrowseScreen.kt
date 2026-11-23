package fr.remotecontrol.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.CatalogueSwitch
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.ScreenHeader
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.WORK_TYPES
import fr.remotecontrol.ui.common.WorkList
import fr.remotecontrol.ui.theme.RcColors

/**
 * Maquettes « Parcourir par type » (filtres de type, ligne « 1 912 films · Titre », grille d'affiches)
 * et « Collection » (titre, compteurs, liste par rang), selon [BrowseUi.collectionId].
 */
@Composable
fun BrowseScreen(
    state: BrowseUi,
    server: ServerState,
    onPlayableOnly: (Boolean) -> Unit,
    onType: (String?) -> Unit,
    onToggleSort: () -> Unit,
    onOpen: (WorkSummary) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(RcColors.bg)) {
        if (state.collectionId != null) {
            CollectionContent(state, server, onPlayableOnly, onOpen, onBack)
        } else {
            TypeContent(state, server, onPlayableOnly, onType, onToggleSort, onOpen)
        }
    }
}

@Composable
private fun TypeContent(
    state: BrowseUi,
    server: ServerState,
    onPlayableOnly: (Boolean) -> Unit,
    onType: (String?) -> Unit,
    onToggleSort: () -> Unit,
    onOpen: (WorkSummary) -> Unit,
) {
    ScreenHeader(Texts.sectionBrowse, server)
    TypeChips(state.type, onType)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clickable(onClickLabel = Texts.changeSort, role = Role.Button, onClick = onToggleSort),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(RcIcons.Sort, contentDescription = null, tint = RcColors.text2, modifier = Modifier.size(18.dp))
            Text(
                Texts.browseSummary(state.type, state.works.size, state.recent),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = RcColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CatalogueSwitch(state.playableOnly, onPlayableOnly)
    }
    if (state.loaded && state.works.isEmpty()) {
        EmptyMessage()
        return
    }
    // Clés stables (id) : la grille tient les ~3 600 films sans recomposer les cellules au défilement.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(state.works, key = { it.id }, contentType = { "work" }) { work ->
            GridCell(work, onOpen)
        }
    }
}

@Composable
private fun TypeChips(selected: String?, onType: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") { TypeChip(Texts.allTypes, selected == null) { onType(null) } }
        items(WORK_TYPES, key = { it }) { type -> TypeChip(Texts.typeLabel(type), selected == type) { onType(type) } }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) RcColors.accentSoft else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, RcColors.line, shape))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = if (selected) 8.dp else 14.dp, end = if (selected) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(RcIcons.Check, contentDescription = null, tint = RcColors.accentStrong, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) RcColors.accentStrong else RcColors.text2,
            maxLines = 1,
        )
    }
}

@Composable
private fun GridCell(work: WorkSummary, onOpen: (WorkSummary) -> Unit) {
    Column(Modifier.clickable { onOpen(work) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PosterThumb(
            hash = work.poster,
            title = work.titleFr,
            year = work.year,
            unavailableLabel = if (work.playable) null else Texts.notAvailable,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            Text(
                work.titleFr,
                style = MaterialTheme.typography.labelMedium,
                color = RcColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (work.year != null) {
                Text(work.year.toString(), style = MaterialTheme.typography.bodySmall, color = RcColors.text3)
            }
        }
    }
}

@Composable
private fun CollectionContent(
    state: BrowseUi,
    server: ServerState,
    onPlayableOnly: (Boolean) -> Unit,
    onOpen: (WorkSummary) -> Unit,
    onBack: () -> Unit,
) {
    ScreenHeader(null, server, onBack)
    WorkList(state.works, onOpen, Modifier.fillMaxSize(), ranks = state.ranks) {
        item(key = "title") {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                state.collection?.let { c ->
                    Text(
                        Texts.collectionSummary(c.count, c.playableCount, ranked = state.ranks.isNotEmpty()),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                        color = RcColors.text2,
                    )
                }
            }
        }
        item(key = "switch") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                CatalogueSwitch(state.playableOnly, onPlayableOnly)
            }
        }
        if (state.loaded && state.works.isEmpty()) item(key = "empty") { EmptyMessage() }
    }
}

@Composable
private fun EmptyMessage() {
    Text(
        Texts.nothingToShow,
        style = MaterialTheme.typography.bodyMedium,
        color = RcColors.text2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}
