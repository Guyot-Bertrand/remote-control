package fr.remotecontrol.ui.search

import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val filter = CatalogFilter()
    private val vms = mutableListOf<SearchViewModel>()

    /** Requêtes FTS réellement envoyées à la base. */
    private val matches: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun TestScope.viewModel(): SearchViewModel {
        val real = db.catalogDao()
        val spy = object : CatalogDao by real {
            override fun searchHits(match: String, playableOnly: Boolean, limit: Int): Flow<List<SearchHit>> {
                matches += match
                return real.searchHits(match, playableOnly, limit)
            }
        }
        val vm = SearchViewModel(spy, filter).also { vms += it }
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.tvView.collect {} }
        return vm
    }

    // Room répond depuis ses propres threads : on attend en temps réel, hors de l'horloge virtuelle.
    private suspend fun SearchViewModel.await(predicate: (SearchUi) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first(predicate) } }

    private fun SearchUi.ids() = results.map { it.id }

    @Test fun query_folds_accents() = runTest {
        val vm = viewModel()
        vm.setPlayableOnly(false)
        vm.onQuery("prenom")
        advanceTimeBy(200)
        val both = vm.await { it.results.size == 2 }
        assertEquals(listOf(3L, 4L), both.ids())
        assertEquals(listOf("Alexandre de La Patellière", "Matthieu Delaporte"), both.results[0].directors)
        vm.setPlayableOnly(true)
        assertEquals(listOf(3L), vm.await { it.playableOnly && it.results.size == 1 }.ids())
    }

    @Test fun blank_query_empty() = runTest {
        val vm = viewModel()
        vm.onQuery("prenom")
        advanceTimeBy(200)
        vm.await { it.results.isNotEmpty() }
        vm.onQuery("  ")
        advanceTimeBy(200)
        val ui = vm.await { it.results.isEmpty() }
        assertEquals("  ", ui.query)
        assertEquals(listOf("prenom*"), matches.toList())
    }

    @Test fun filter_toggle_from_search() = runTest {
        val vm = viewModel()
        vm.onQuery("prenom")
        advanceTimeBy(200)
        assertEquals(listOf(3L), vm.await { it.results.isNotEmpty() }.ids())
        vm.setPlayableOnly(false)
        assertEquals(listOf(3L, 4L), vm.await { !it.playableOnly && it.results.size == 2 }.ids())
        assertEquals(false, filter.playableOnly.value)
    }

    @Test fun debounce_only_last_query() = runTest {
        val vm = viewModel()
        vm.onQuery("a")
        advanceTimeBy(20)
        vm.onQuery("av")
        advanceTimeBy(20)
        vm.onQuery("ava")
        advanceTimeBy(200)
        assertEquals(listOf(1L), vm.await { it.results.isNotEmpty() }.ids())
        assertEquals(listOf("ava*"), matches.toList())
    }

    @Test fun query_visible_before_debounce() = runTest {
        val vm = viewModel()
        vm.onQuery("ava")
        runCurrent() // sans avancer l'horloge : le délai de frappe n'est pas écoulé
        assertEquals("ava", vm.state.value.query)
        assertEquals(emptyList<WorkSummary>(), vm.state.value.results)
        assertEquals(emptyList<String>(), matches.toList())
    }

    @Test fun tv_view_waits_for_end_of_typing() = runTest {
        val vm = viewModel()
        // Vues publiées, avec l'heure (virtuelle) de publication.
        val published = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
        backgroundScope.launch { vm.tvView.collect { v -> if (v != null) published += testScheduler.currentTime to v.query } }
        vm.onQuery("a")
        advanceTimeBy(200)
        vm.onQuery("av")
        advanceTimeBy(200)
        vm.onQuery("ava")
        val typed = testScheduler.currentTime
        val v = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.tvView.first { it?.query == "ava" } } }!!
        assertEquals("search", v.screen)
        assertEquals(listOf(1L), v.sections.flatMap { it.items }.map { it.id }.distinct())
        // Rien pendant la frappe (pauses de 200 ms) ; la saisie finale, 300 ms après la dernière touche.
        assertEquals(listOf("ava"), published.map { it.second })
        assertTrue(published.single().first >= typed + TV_SEARCH_DEBOUNCE_MS)
    }

    @Test fun people_and_alternative_titles_sections() = runTest {
        val vm = viewModel()
        vm.setPlayableOnly(false)
        vm.onQuery("lea mysi")
        advanceTimeBy(200)
        val ui = vm.await { it.sections.people.isNotEmpty() }
        assertEquals(listOf(PersonHit("Léa Mysius", directing = true, workCount = 1)), ui.sections.people)
        assertEquals(listOf(1L), ui.sections.works.map { it.work.id })
        vm.onQuery("etranger")
        advanceTimeBy(200)
        val aka = vm.await { it.sections.aka.isNotEmpty() }
        assertEquals(listOf(2L), aka.sections.aka.map { it.work.id })
        assertEquals(listOf(2L), aka.ids())
    }
}
