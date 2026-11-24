package fr.remotecontrol.ui.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.EpisodeRow
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.player.SheetAction
import fr.remotecontrol.player.live
import fr.remotecontrol.player.playsEpisode
import fr.remotecontrol.player.playsSeason
import fr.remotecontrol.player.sheetAction
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.player.TvSheet
import fr.remotecontrol.player.TvStatus
import fr.remotecontrol.player.highlightEpisode
import fr.remotecontrol.player.sortQualities
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.PlayAction
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    /** Lire, rouvrir la télécommande (cet épisode joue sur la télé), ou lecteur absent du PC. */
    val action: SheetAction = SheetAction.Play,
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
    val launching: Boolean = false,
    /** Qualités disponibles dans la saison (« FHD »), de la meilleure à la moins bonne. */
    val qualities: List<String> = emptyList(),
    /** Un épisode de cette saison joue sur la télé en ce moment. */
    val playingHere: Boolean = false,
    /** Le lecteur n'est pas installé sur le PC (bandeau ; lecture impossible). */
    val playerMissing: Boolean = false,
)

private data class Catalog(
    val season: SeasonEntity,
    val work: WorkEntity?,
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeRow>,
    val qualities: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonViewModel(
    private val seasonId: Long,
    dao: CatalogDao,
    api: RcApi,
    launcher: PlayLauncher,
    tvStatus: TvStatus,
    /** Lecture en cours sur la télé ([fr.remotecontrol.player.RemoteController.snapshot]). */
    live: Flow<PlayerSnapshotDto?> = flowOf(null),
    /** Le serveur connecté signale l'absence du lecteur ([fr.remotecontrol.discovery.ServerLocator.playerMissing]). */
    playerMissing: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val playAction = PlayAction(viewModelScope, launcher)

    /** Envoyée à la télé une fois à l'ouverture (hors ligne, l'échec est ignoré), puis suivie en direct. */
    private val tvSheet = TvSheet(viewModelScope, api, tvStatus, "season", seasonId)

    /** Navigation demandée (télécommande après un lancement) ; livrée seulement si l'écran l'écoute. */
    val events: Flow<NavEvent> = playAction.events

    private val catalog: Flow<Catalog?> = dao.season(seasonId).flatMapLatest { season ->
        if (season == null) {
            flowOf(null)
        } else {
            combine(
                dao.work(season.workId),
                dao.seasonsOfWork(season.workId),
                dao.episodesOfSeason(seasonId),
                dao.seasonQualities(seasonId),
            ) { w, all, eps, q ->
                Catalog(season, w, all, eps, sortQualities(q))
            }
        }
    }

    private val current: Flow<CurrentDto?> = live.map { it.live() }.distinctUntilChanged()

    val state: StateFlow<SeasonUi?> = combine(
        catalog, playAction.message, tvSheet.onTv, playAction.launching, combine(current, playerMissing, ::Pair),
    ) { c, msg, tv, busy, (playing, missing) ->
        c?.let { toUi(it, msg, tv).copy(launching = busy).withPlayback(playing, playerAvailable = !missing) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** La base a répondu sans cette saison. */
    val missing: StateFlow<Boolean> = dao.season(seasonId).map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    fun play(episodeId: Long, mode: StartMode) = playAction.launch(PlayTarget(TargetKind.Episode, episodeId), mode)

    /** Retour sur l'écran : renvoie la fiche à la télé si elle montre autre chose et que rien n'est en lecture. */
    fun onScreenResumed() = tvSheet.onScreenResumed()

    fun clearMessage() {
        playAction.message.value = null
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
            qualities = c.qualities,
        )
    }
}

/** Épisode en cours sur la télé : « Ouvrir la télécommande » à la place de la lecture. */
private fun SeasonUi.withPlayback(playing: CurrentDto?, playerAvailable: Boolean) = copy(
    playingHere = playing?.playsSeason(season.id) == true,
    playerMissing = !playerAvailable,
    episodes = episodes.map { it.copy(action = sheetAction(playing?.playsEpisode(it.id) == true, playerAvailable)) },
)

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
