package fr.remotecontrol.ui.browse

import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.SORT_RECENT
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val filter = CatalogFilter()
    private val vms = mutableListOf<BrowseViewModel>()

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun viewModel(route: BrowseRoute) = BrowseViewModel(route, db.catalogDao(), filter).also { vms += it }

    // Room exécute ses requêtes hors du dispatcher de test : on attend l'état voulu.
    private suspend fun BrowseViewModel.await(predicate: (BrowseUi) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private fun BrowseUi.ids() = works.map { it.id }

    @Test fun type_title_and_filter() = runBlocking {
        val vm = viewModel(BrowseRoute(type = "film"))
        val ui = vm.await { it.works.isNotEmpty() }
        assertEquals("Films", ui.title)
        assertEquals(listOf(1L, 3L), ui.ids())
        vm.setPlayableOnly(false)
        assertEquals(listOf(2L, 1L, 3L), vm.await { !it.playableOnly && it.works.size == 3 }.ids())
        assertEquals(false, filter.playableOnly.value)
    }

    @Test fun collection_title_and_order() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute(collectionId = 2))
        val ui = vm.await { it.works.isNotEmpty() && it.title.isNotEmpty() }
        assertEquals("Alien [1979-]", ui.title)
        assertEquals(listOf(2L), ui.ids())
        assertEquals(1, ui.collection?.count)
        assertEquals(0, ui.collection?.playableCount)
    }

    @Test fun collection_exposes_ranks() = runBlocking {
        val vm = viewModel(BrowseRoute(collectionId = 1))
        val ui = vm.await { it.works.isNotEmpty() && it.ranks.isNotEmpty() }
        assertEquals(mapOf(1L to "2017"), ui.ranks)
    }

    @Test fun see_all_recent_lists_every_type_newest_first() = runBlocking {
        val vm = viewModel(BrowseRoute(sort = SORT_RECENT))
        val ui = vm.await { it.works.isNotEmpty() }
        assertTrue(ui.recent)
        assertEquals(null, ui.type)
        assertEquals(listOf(5L, 3L, 1L), ui.ids())
        vm.setPlayableOnly(false)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), vm.await { it.works.size == 5 }.ids())
    }

    @Test fun all_types_by_title() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute())
        // Tri par titre sans accents : Alien, Ava, Back to Life, Le Prénom (3 puis 4).
        assertEquals(listOf(2L, 1L, 5L, 3L, 4L), vm.await { it.works.size == 5 }.ids())
    }

    @Test fun sort_toggle_and_type_change() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute(type = "film"))
        vm.await { it.works.size == 3 }
        vm.toggleSort()
        assertEquals(listOf(3L, 2L, 1L), vm.await { it.recent && it.works.firstOrNull()?.id == 3L }.ids())
        vm.setType("theatre")
        val ui = vm.await { it.type == "theatre" && it.works.size == 1 }
        assertEquals("Théâtre", ui.title)
        assertEquals(listOf(4L), ui.ids())
    }
}
