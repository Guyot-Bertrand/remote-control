package fr.remotecontrol.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.CollectionSummary
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.mirror.browseView
import fr.remotecontrol.mirror.collectionView
import fr.remotecontrol.net.TvViewDto
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.SORT_RECENT
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Parcours par type (grille, [type] nul : tous types) ou par collection ([collectionId], liste par rang).
 * [recent] : tri par ajout (id décroissant) au lieu du titre ; sans effet sur une collection.
 * [loaded] : la base a répondu (distingue « pas encore chargé » de « rien à afficher »).
 */
data class BrowseUi(
    val title: String,
    val works: List<WorkSummary> = emptyList(),
    val playableOnly: Boolean = true,
    val type: String? = null,
    val recent: Boolean = false,
    val collectionId: Long? = null,
    val collection: CollectionSummary? = null,
    val ranks: Map<Long, String> = emptyMap(),
    val loaded: Boolean = false,
)

private data class Selection(val type: String?, val recent: Boolean)

private data class Answer(val selection: Selection, val playableOnly: Boolean, val works: List<WorkSummary>)

private fun typeTitle(type: String?) = type?.let(Texts::typeLabel) ?: Texts.allWorks

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    route: BrowseRoute,
    private val dao: CatalogDao,
    private val filter: CatalogFilter,
) : ViewModel() {
    private val selection = MutableStateFlow(Selection(route.type, route.sort == SORT_RECENT))
    private val collectionId = route.collectionId

    private val initial = if (collectionId != null) {
        BrowseUi(title = "", collectionId = collectionId)
    } else {
        BrowseUi(title = typeTitle(route.type), type = route.type, recent = route.sort == SORT_RECENT)
    }

    val state: StateFlow<BrowseUi> = (if (collectionId != null) collectionState(collectionId) else typeState())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Première œuvre visible dans la liste (ancre de la vue publiée sur la télé). */
    private val firstVisible = MutableStateFlow(0)

    /**
     * Vue à publier sur la télé : la liste affichée autour de la première œuvre visible. Nulle tant que la liste
     * n'est pas chargée ; inchangée (pas de nouvel envoi) tant que l'ancre ne bouge pas.
     */
    val tvView: StateFlow<TvViewDto?> = combine(state, firstVisible) { ui, first -> ui.tvView(first) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Le type, le tri et le filtre choisis passent tout de suite dans l'état ; la liste n'y entre que lorsqu'elle
     * répond à ce choix-là (sinon vide, [BrowseUi.loaded] faux) : jamais les films sous la puce « Théâtre ».
     */
    private fun typeState(): Flow<BrowseUi> {
        val answers = combine(filter.playableOnly, selection, ::Pair).flatMapLatest { (playable, sel) ->
            val works = if (sel.type == null) dao.allWorks(playable) else dao.worksByType(sel.type, playable)
            works.map<List<WorkSummary>, Answer?> { list -> Answer(sel, playable, if (sel.recent) list.sortedByDescending { it.id } else list) }
        }.onStart { emit(null) }
        return combine(selection, filter.playableOnly, answers) { sel, playable, answer ->
            val current = answer != null && answer.selection == sel && answer.playableOnly == playable
            BrowseUi(
                title = typeTitle(sel.type),
                works = if (current) answer.works else emptyList(),
                playableOnly = playable,
                type = sel.type,
                recent = sel.recent,
                loaded = current,
            )
        }
    }

    private fun collectionState(id: Long): Flow<BrowseUi> {
        // Le nom et les compteurs viennent de collections(false) : la collection reste connue même filtrée à vide.
        val info = dao.collections(false).map { all -> all.firstOrNull { it.id == id } }
        val ranks = dao.collectionRanks(id).map { rows -> rows.associate { it.workId to it.rank } }
        val works = filter.playableOnly.flatMapLatest { p -> dao.worksInCollection(id, p).map { p to it } }
        return combine(works, info, ranks) { (playable, list), collection, rankMap ->
            BrowseUi(
                title = collection?.name.orEmpty(),
                works = list,
                playableOnly = playable,
                collectionId = id,
                collection = collection,
                ranks = rankMap,
                loaded = true,
            )
        }
    }

    /** Index de la première œuvre visible, relevé au défilement. */
    fun onFirstVisible(index: Int) {
        firstVisible.value = index
    }

    // Un nouveau choix fait repartir la grille du haut (la liste d'une collection, elle, garde sa position).
    fun setPlayableOnly(value: Boolean) {
        if (collectionId == null) firstVisible.value = 0
        filter.playableOnly.value = value
    }

    fun setType(type: String?) {
        firstVisible.value = 0
        selection.value = selection.value.copy(type = type)
    }

    fun toggleSort() {
        firstVisible.value = 0
        selection.value = selection.value.copy(recent = !selection.value.recent)
    }
}

private fun BrowseUi.tvView(first: Int): TvViewDto? = when {
    !loaded -> null
    // Collection encore inconnue (nom pas encore lu) : rien à publier.
    collectionId != null -> collection?.let { c ->
        collectionView(collectionId, c.name, Texts.collectionSummary(c.count, c.playableCount, ranks.isNotEmpty()), works, first)
    }
    else -> browseView(type, title, Texts.browseSummary(type, works.size, recent), playableOnly, works, first)
}
