package fr.remotecontrol.ui.browse

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import fr.remotecontrol.ui.common.indexLetter
import fr.remotecontrol.ui.theme.Fraunces
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
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
                // Le compte n'apparaît qu'une fois la liste chargée pour ce choix.
                if (state.loaded) Texts.browseSummary(state.type, state.works.size, state.recent) else Texts.sortLabel(state.recent),
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
    // Nouveau choix (type, tri, filtre) : la grille repart du haut ; au retour d'une fiche, la position est gardée.
    val gridState = rememberSaveable(state.type, state.recent, state.playableOnly, saver = LazyGridState.Saver) {
        LazyGridState()
    }
    // Clés stables (id) : la grille tient les ~3 600 films sans recomposer les cellules au défilement.
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.works, key = { it.id }, contentType = { "work" }) { work ->
                GridCell(work, onOpen)
            }
        }
        if (!state.recent) FastScroller(gridState, state.works, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * Défilement rapide de la maquette « Parcourir » (tri par titre) : poignée au bord droit, à faire glisser,
 * et bulle laiton avec la lettre du premier titre affiché, pendant le défilement.
 */
@Composable
private fun FastScroller(grid: LazyGridState, works: List<WorkSummary>, modifier: Modifier = Modifier) {
    if (works.size < FAST_SCROLL_MIN) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(grid) {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            (grid.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
        }
    }
    val active = dragging || grid.isScrollInProgress
    val alpha by animateFloatAsState(if (active) 1f else 0f, label = "fastScroll")
    BoxWithConstraints(modifier.fillMaxHeight().padding(vertical = 8.dp)) {
        val track = maxHeight - THUMB_HEIGHT
        val offset = track * fraction
        if (alpha > 0f) {
            Row(
                Modifier.offset { IntOffset(0, offset.roundToPx()) }.padding(end = 4.dp).alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp))
                        .background(RcColors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        indexLetter(works.getOrNull(grid.firstVisibleItemIndex)?.titleFr.orEmpty()),
                        style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                        color = RcColors.onAccent,
                    )
                }
                Box(Modifier.size(width = 4.dp, height = THUMB_HEIGHT).clip(RoundedCornerShape(2.dp)).background(RcColors.text3))
            }
        }
        // Zone de saisie étroite, au bord : les affiches de la dernière colonne restent touchables.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(20.dp)
                .fillMaxHeight()
                .pointerInput(works) {
                    fun jump(y: Float) {
                        val f = (y / size.height).coerceIn(0f, 1f)
                        scope.launch { grid.scrollToItem((f * (works.size - 1)).roundToInt()) }
                    }
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            jump(it.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    }
                },
        )
    }
}

private const val FAST_SCROLL_MIN = 60
private val THUMB_HEIGHT = 40.dp

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
    Column(
        Modifier.clickable(onClickLabel = Texts.openWork, role = Role.Button) { onOpen(work) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PosterThumb(
            hash = work.poster,
            title = work.titleFr,
            year = work.year,
            progress = work.progress?.toFloat(),
            watched = work.watched,
            unavailableLabel = if (work.playable) null else Texts.unavailableLabel(work.absentVolume),
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
