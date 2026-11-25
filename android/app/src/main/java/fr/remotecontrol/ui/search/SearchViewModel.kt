package fr.remotecontrol.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.buildFtsQuery
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.mirror.searchView
import fr.remotecontrol.net.TvViewDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * [resultsFor] : la saisie à laquelle [results] répondent (nulle avant la première réponse) ; [sections] :
 * les mêmes résultats, répartis en personnes, œuvres et titres alternatifs.
 */
data class SearchUi(
    val query: String = "",
    val results: List<WorkSummary> = emptyList(),
    val sections: SearchSections = SearchSections(),
    val playableOnly: Boolean = true,
    val resultsFor: String? = null,
)

/** Réponse de la base pour la saisie [text], déjà répartie en [sections]. */
private data class Answer(val text: String, val rows: List<SearchHit>, val sections: SearchSections)

/** Délai de frappe avant d'interroger la base. */
const val SEARCH_DEBOUNCE_MS = 150L

/** Fin de frappe attendue avant de publier la recherche sur la télé. */
const val TV_SEARCH_DEBOUNCE_MS = 300L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val dao: CatalogDao,
    private val filter: CatalogFilter,
) : ViewModel() {
    private val query = MutableStateFlow("")

    private val results = combine(query.debounce(SEARCH_DEBOUNCE_MS), filter.playableOnly, ::Pair)
        .distinctUntilChanged()
        .flatMapLatest { (text, playable) ->
            val match = buildFtsQuery(text)
            val rows = if (match == null) flowOf(emptyList()) else dao.searchHits(match, playable)
            // Répartition en sections hors du thread principal, une fois par réponse.
            // (le délai de frappe, en amont, reste sur le dispatcher de l'appelant).
            rows.map { Answer(text, it, searchSections(text, it)) }.flowOn(Dispatchers.Default)
        }
        // Sans valeur initiale, l'état attendrait la première réponse (après le délai de frappe).
        .onStart { emit(Answer("", emptyList(), SearchSections())) }

    val state: StateFlow<SearchUi> = combine(query, results, filter.playableOnly) { q, a, p ->
        SearchUi(query = q, results = a.rows.map { it.work }, sections = a.sections, playableOnly = p, resultsFor = a.text)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    /**
     * Vue à publier sur la télé : la saisie et les sections qui lui répondent, une fois la frappe finie (saisie
     * stable depuis [TV_SEARCH_DEBOUNCE_MS]) ; entre-temps, la télé garde la vue précédente. Les résultats à l'écran,
     * eux, n'attendent pas.
     */
    val tvView: StateFlow<TvViewDto?> = combine(query.debounce(TV_SEARCH_DEBOUNCE_MS), state) { settled, ui ->
        ui.resultsFor?.takeIf { it == settled }?.let { searchView(it, ui.sections) }
    }
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQuery(text: String) {
        query.value = text
    }

    fun setPlayableOnly(value: Boolean) {
        filter.playableOnly.value = value
    }
}
