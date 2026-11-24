package fr.remotecontrol.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.buildFtsQuery
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

/** Délai de frappe avant d'interroger la base. */
const val SEARCH_DEBOUNCE_MS = 150L

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
            rows.map { text to it }
        }
        // Sans valeur initiale, l'état attendrait la première réponse (après le délai de frappe).
        .onStart { emit("" to emptyList()) }

    /** Répartition recalculée seulement quand la réponse change (pas à chaque frappe). */
    private var lastSections: Triple<String, List<SearchHit>, SearchSections>? = null

    private fun sections(text: String, rows: List<SearchHit>): SearchSections {
        lastSections?.let { (t, r, s) -> if (t == text && r === rows) return s }
        return searchSections(text, rows).also { lastSections = Triple(text, rows, it) }
    }

    val state: StateFlow<SearchUi> = combine(query, results, filter.playableOnly) { q, (text, rows), p ->
        SearchUi(query = q, results = rows.map { it.work }, sections = sections(text, rows), playableOnly = p, resultsFor = text)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(text: String) {
        query.value = text
    }

    fun setPlayableOnly(value: Boolean) {
        filter.playableOnly.value = value
    }
}
