package fr.remotecontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.CollectionSummary
import fr.remotecontrol.data.db.ResumeRow
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class ResumeItem(
    val workId: Long,
    val seasonId: Long?,
    val title: String,
    val poster: String?,
    val progress: Float,
    val label: String,
)

fun toResumeItem(row: ResumeRow): ResumeItem {
    val title = if (row.targetKind == "episode" && row.seasonNumber != null && row.episodeNumber != null) {
        Texts.episodeTitle(row.titleFr, row.seasonNumber, row.episodeNumber)
    } else {
        row.titleFr
    }
    val duration = row.duration
    val progress = if (duration == null || duration <= 0.0) 0f else (row.position / duration).toFloat().coerceIn(0f, 1f)
    return ResumeItem(row.workId, row.seasonId, title, row.poster, progress, Texts.resumeAt(row.position))
}

data class HomeUi(
    val resume: List<ResumeItem> = emptyList(),
    val recent: List<WorkSummary> = emptyList(),
    val collections: List<CollectionSummary> = emptyList(),
    val playableOnly: Boolean = true,
    val catalogEmpty: Boolean = false,
    val server: ServerState = ServerState.Searching,
)

private data class Filtered(
    val playableOnly: Boolean,
    val recent: List<WorkSummary>,
    val collections: List<CollectionSummary>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    dao: CatalogDao,
    private val filter: CatalogFilter,
    locator: ServerLocator,
) : ViewModel() {
    private val filtered = filter.playableOnly.flatMapLatest { playable ->
        combine(dao.recentWorks(playable, 20), dao.collections(playable)) { recent, collections ->
            Filtered(playable, recent, collections)
        }
    }

    val state: StateFlow<HomeUi> = combine(
        dao.resumeRows(10),
        filtered,
        dao.observeWorkCount(),
        locator.state,
    ) { resume, f, count, server ->
        HomeUi(
            resume = resume.map(::toResumeItem),
            recent = f.recent,
            collections = f.collections,
            playableOnly = f.playableOnly,
            catalogEmpty = count == 0,
            server = server,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun setPlayableOnly(value: Boolean) {
        filter.playableOnly.value = value
    }
}
