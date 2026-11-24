package fr.remotecontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.CollectionSummary
import fr.remotecontrol.data.db.ResumeRow
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.data.sync.SyncOutcome
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.remainingMinutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ResumeItem(
    val targetKind: String,
    val targetId: Long,
    val workId: Long,
    val seasonId: Long?,
    val title: String,
    val poster: String?,
    val progress: Float,
    val label: String,
)

/** Titre de l'œuvre ; pour un épisode, la ligne de détail commence par « S2 É3 · ». */
fun toResumeItem(row: ResumeRow): ResumeItem {
    val episode = if (row.targetKind == "episode" && row.seasonNumber != null && row.episodeNumber != null) {
        Texts.episodeShort(row.seasonNumber, row.episodeNumber)
    } else {
        null
    }
    val duration = row.duration
    val known = duration != null && duration > 0.0
    val progress = if (known) (row.position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val left = if (known) Texts.remaining(remainingMinutes(row.position, duration)) else Texts.resumeAt(row.position)
    return ResumeItem(
        row.targetKind, row.targetId, row.workId, row.seasonId, row.titleFr, row.poster, progress, Texts.details(episode, left),
    )
}

/**
 * État particulier de l'accueil : [NoServer] est un bandeau ; les autres remplacent le contenu (catalogue vide) :
 * recherche du serveur, synchronisation en cours, catalogue vide sur le serveur ([Empty]), ou catalogue jamais
 * reçu hors synchronisation ([NotSynced] : première synchronisation en échec).
 */
enum class HomeBanner { Searching, NoServer, Syncing, Empty, NotSynced }

/**
 * Sans serveur : « Aucun serveur trouvé » (avec accès aux réglages), catalogue vide ou non.
 * Catalogue vide : recherche du serveur, synchronisation seulement pendant qu'elle tourne ([syncing]) ; sinon,
 * catalogue vide sur le serveur si une synchronisation a abouti ([everSynced]), ou jamais reçu.
 */
fun homeBanner(server: ServerState, catalogEmpty: Boolean, everSynced: Boolean = false, syncing: Boolean = false): HomeBanner? =
    when {
        server == ServerState.NotFound -> HomeBanner.NoServer
        !catalogEmpty -> null
        server !is ServerState.Connected -> HomeBanner.Searching
        syncing -> HomeBanner.Syncing
        everSynced -> HomeBanner.Empty
        else -> HomeBanner.NotSynced
    }

data class HomeUi(
    val resume: List<ResumeItem> = emptyList(),
    val recent: List<WorkSummary> = emptyList(),
    val collections: List<CollectionSummary> = emptyList(),
    val playableOnly: Boolean = true,
    val catalogEmpty: Boolean = false,
    val server: ServerState = ServerState.Searching,
    val banner: HomeBanner? = null,
    /** Échec de « Resynchroniser » lancé depuis l'accueil (message en français). */
    val message: String? = null,
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
    private val sync: SyncRepository,
) : ViewModel() {
    /** Échec d'un « Resynchroniser », avec l'heure de dernière synchronisation réussie connue à ce moment. */
    private val failure = MutableStateFlow<Pair<String, Long?>?>(null)

    // Le message ne vaut que tant qu'aucune synchronisation n'a réussi depuis (automatique ou non).
    private val syncState = combine(sync.syncing, sync.syncedAt, failure) { syncing, at, f ->
        Triple(syncing, at != null, f?.takeIf { it.second == at }?.first)
    }

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
        syncState,
    ) { resume, f, count, server, (syncing, everSynced, msg) ->
        HomeUi(
            resume = resume.map(::toResumeItem),
            recent = f.recent,
            collections = f.collections,
            playableOnly = f.playableOnly,
            catalogEmpty = count == 0,
            server = server,
            banner = homeBanner(server, count == 0, everSynced, syncing),
            message = msg.takeIf { count == 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun setPlayableOnly(value: Boolean) {
        filter.playableOnly.value = value
    }

    /** « Resynchroniser » du catalogue vide : synchronisation complète. */
    fun resync() {
        failure.value = null
        viewModelScope.launch {
            val outcome = sync.sync(forceFull = true)
            if (outcome is SyncOutcome.Failed) failure.value = outcome.error.messageFr() to sync.syncedAt.first()
        }
    }
}
