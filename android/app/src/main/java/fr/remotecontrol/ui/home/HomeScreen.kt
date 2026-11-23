package fr.remotecontrol.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.ConnectionChip
import fr.remotecontrol.ui.common.PosterThumb
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.WORK_TYPES
import fr.remotecontrol.ui.theme.RcColors

@Composable
fun HomeScreen(
    state: HomeUi,
    onPlayableOnly: (Boolean) -> Unit,
    onOpenWork: (Long) -> Unit,
    onOpenSeason: (Long) -> Unit,
    onOpenType: (String) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onSeeAllRecent: () -> Unit,
    onRemote: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(RcColors.bg)) {
        HomeHeader(state.server, onSettings)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.server == ServerState.NotFound) {
                item { Banner(Texts.noServer, RcColors.warnSoft, Texts.settings, onSettings) }
            }
            if (state.catalogEmpty) {
                item { Banner(Texts.catalogEmpty, RcColors.surface2) }
            }
            item { FilterRow(state.playableOnly, onPlayableOnly, onRemote) }
            if (state.resume.isNotEmpty()) {
                item { SectionTitle(Texts.resume) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.resume, key = { "${it.targetKind}/${it.targetId}" }) { item ->
                            ResumeCard(item) {
                                if (item.seasonId != null) onOpenSeason(item.seasonId) else onOpenWork(item.workId)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (state.recent.isNotEmpty()) {
                item { SectionTitle(Texts.sectionRecent, Texts.seeAll, onSeeAllRecent) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.recent, key = { it.id }) { work ->
                            Column(
                                Modifier.width(96.dp).clickable { onOpenWork(work.id) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PosterThumb(
                                    hash = work.poster,
                                    title = work.titleFr,
                                    year = work.year,
                                    unavailableLabel = if (work.playable) null else Texts.notAvailable,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    work.titleFr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RcColors.text2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item { SectionTitle(Texts.sectionBrowse) }
            item { TypePills(onOpenType) }
            if (state.collections.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                item { SectionTitle(Texts.sectionCollections) }
                items(state.collections, key = { it.id }) { c ->
                    CollectionRow(c.name, Texts.collectionCounts(c.count, c.playableCount)) { onOpenCollection(c.id) }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(server: ServerState, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(RcColors.accent))
            Text(
                Texts.appName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConnectionChip(server)
        IconButton(onClick = onSettings) {
            Icon(RcIcons.Settings, contentDescription = Texts.settings, tint = RcColors.text)
        }
    }
}

@Composable
private fun Banner(text: String, background: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = RcColors.text, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = RcColors.accentStrong)
            }
        }
    }
}

@Composable
private fun FilterRow(playableOnly: Boolean, onPlayableOnly: (Boolean) -> Unit, onRemote: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RcFilterChip(Texts.playableOnly, playableOnly) { onPlayableOnly(true) }
        RcFilterChip(Texts.wholeCatalogue, !playableOnly) { onPlayableOnly(false) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRemote) {
            Text(Texts.remote, style = MaterialTheme.typography.labelLarge, color = RcColors.accentStrong)
        }
    }
}

@Composable
private fun RcFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = RcColors.text2,
            selectedContainerColor = RcColors.accentSoft,
            selectedLabelColor = RcColors.accentStrong,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = RcColors.line,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.titleSmall, color = RcColors.accentStrong)
            }
        }
    }
}

@Composable
private fun ResumeCard(item: ResumeItem, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosterThumb(
            hash = item.poster,
            title = item.title,
            progress = item.progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.label, style = MaterialTheme.typography.bodySmall, color = RcColors.text2, maxLines = 1)
        }
    }
}

@Composable
private fun TypePills(onOpenType: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WORK_TYPES.forEach { type ->
            Text(
                Texts.typeLabel(type),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(RcColors.surface)
                    .border(1.dp, RcColors.line, RoundedCornerShape(999.dp))
                    .clickable { onOpenType(type) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CollectionRow(name: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(RcColors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RcIcons.Collection, contentDescription = null, tint = RcColors.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = RcColors.text2)
        }
        Icon(RcIcons.ChevronRight, contentDescription = null, tint = RcColors.text3, modifier = Modifier.size(20.dp))
    }
}
