package fr.remotecontrol.ui.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.EpisodeRow
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayOutcome
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.player.highlightEpisode
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [label] : « S01E02 », suivi de « — titre » s'il existe. [status] : « Vu », « Entamé — 5:00 » ou nul.
 * [progress] : avancement d'un épisode entamé (barre de l'épisode mis en évidence), nul sinon.
 */
data class EpisodeUi(
    val id: Long,
    val label: String,
    val status: String?,
    val highlighted: Boolean,
    val canResume: Boolean,
    val playable: Boolean,
    val watched: Boolean = false,
    val progress: Float? = null,
)

/** [work] et [seasons] : en-tête de la série et sélecteur de saison des maquettes. */
data class SeasonUi(
    val season: SeasonEntity,
    val workTitle: String,
    val episodes: List<EpisodeUi>,
    val message: String? = null,
    val work: WorkEntity? = null,
    val seasons: List<SeasonEntity> = emptyList(),
    val onTv: Boolean = false,
)

private data class Catalog(
    val season: SeasonEntity,
    val work: WorkEntity?,
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeRow>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonViewModel(
    private val seasonId: Long,
    dao: CatalogDao,
    private val api: RcApi,
    private val launcher: PlayLauncher,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val onTv = MutableStateFlow(false)
    private val navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val events: Flow<NavEvent> = navEvents.receiveAsFlow()

    private val catalog: Flow<Catalog?> = dao.season(seasonId).flatMapLatest { season ->
        if (season == null) {
            flowOf(null)
        } else {
            combine(dao.work(season.workId), dao.seasonsOfWork(season.workId), dao.episodesOfSeason(seasonId)) { w, all, eps ->
                Catalog(season, w, all, eps)
            }
        }
    }

    val state: StateFlow<SeasonUi?> = combine(catalog, message, onTv) { c, msg, tv -> c?.let { toUi(it, msg, tv) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val shown = api.tvShow("season", seasonId)
            onTv.value = shown is ApiResult.Ok && shown.value
        }
    }

    fun play(episodeId: Long, mode: StartMode) {
        viewModelScope.launch {
            when (val outcome = launcher.launch(PlayTarget(TargetKind.Episode, episodeId), mode)) {
                PlayOutcome.Started -> navEvents.send(NavEvent.ToRemote)
                is PlayOutcome.Failed -> message.value = outcome.message
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun toUi(c: Catalog, msg: String?, tv: Boolean): SeasonUi {
        val highlight = highlightEpisode(c.episodes)
        return SeasonUi(
            season = c.season,
            workTitle = c.work?.titleFr.orEmpty(),
            episodes = c.episodes.map { it.toUi(c.season.number, it.id == highlight) },
            message = msg,
            work = c.work,
            seasons = c.seasons,
            onTv = tv,
        )
    }
}

private fun EpisodeRow.toUi(seasonNumber: Int, highlighted: Boolean): EpisodeUi {
    val seen = watched == true
    val started = !seen && (position ?: 0.0) > 0
    return EpisodeUi(
        id = id,
        label = Texts.episodeLabel(seasonNumber, number, title),
        status = when {
            seen -> Texts.watched
            started -> Texts.started(position ?: 0.0)
            else -> null
        },
        highlighted = highlighted,
        canResume = started,
        playable = playable,
        watched = seen,
        progress = if (started) duration?.takeIf { it > 0 }?.let { ((position ?: 0.0) / it).coerceIn(0.0, 1.0).toFloat() } else null,
    )
}
