package fr.remotecontrol.ui.browse

import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.collectNow
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.SORT_RECENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList

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

    @Test fun works_carry_progress_and_watched() = runBlocking {
        db.syncDao().upsertProgress(listOf(ProgressEntity("work", 3, null, 7000.0, 7000.0, true, 1.0)))
        val vm = viewModel(BrowseRoute(type = "film"))
        val ui = vm.await { it.works.size == 2 && it.works.any { w -> w.watched } }
        val ava = ui.works.single { it.id == 1L }
        assertEquals(600.0 / 5940.0, ava.progress!!, 1e-6)
        assertEquals(false, ava.watched)
        val prenom = ui.works.single { it.id == 3L }
        assertEquals(null, prenom.progress) // vu : pastille, pas de barre
        assertEquals(true, prenom.watched)
        assertEquals(listOf("Léa Mysius"), ava.directors)
    }

    @Test fun selection_visible_before_works_load() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute(type = "film"))
        vm.await { it.loaded && it.works.size == 3 }
        // Liste thread-safe, collectée hors du thread de Room : le test attend que le collecteur
        // ait lui-même reçu l'état final, plutôt que de se fier à `state.value`.
        val seen = CopyOnWriteArrayList<BrowseUi>()
        val job = launch(Dispatchers.Default) { vm.state.collect { seen += it } }
        vm.setType("theatre")
        withTimeout(5_000) {
            while (seen.none { it.type == "theatre" && it.loaded }) delay(10)
        }
        job.cancelAndJoin()
        val theatre = seen.filter { it.type == "theatre" }
        // Le filtre choisi s'affiche tout de suite, sans jamais montrer les films sous « Théâtre ».
        assertTrue(theatre.all { ui -> ui.works.all { it.type == "theatre" } })
        assertTrue(theatre.filter { !it.loaded }.all { it.works.isEmpty() })
        assertEquals(listOf(4L), theatre.last().ids())
    }

    @Test fun progress_change_updates_live() = runBlocking {
        val vm = viewModel(BrowseRoute(type = "film"))
        vm.await { it.loaded && it.works.size == 2 }
        db.syncDao().upsertProgress(listOf(ProgressEntity("work", 3, null, 7000.0, 7000.0, true, 1.0)))
        // Room doit invalider la requête via la sous-requête sur `progress`.
        vm.await { ui -> ui.works.any { it.id == 3L && it.watched } }
        Unit
    }

    @Test fun browse_publishes_on_anchor_change_only() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute())
        vm.await { it.loaded && it.works.size == 5 }
        withTimeout(5_000) { vm.tvView.first { it?.anchor == 2L } } // première œuvre : Alien
        val seen = collectNow(vm.tvView.filterNotNull())
        vm.onFirstVisible(3)
        vm.onFirstVisible(3)
        vm.onFirstVisible(4)
        withTimeout(5_000) { vm.tvView.first { it?.anchor == 4L } }
        seen.stop()
        // Alien, Ava, Back to Life, Le Prénom (3 puis 4) : ancres 3 puis 4, la vue de départ comprise.
        assertEquals(listOf(2L, 3L, 4L), seen.items.map { it.anchor })
        val last = seen.items.last()
        assertEquals("browse", last.screen)
        assertEquals("Toutes les œuvres", last.title)
        assertEquals(5, last.position?.index)
        assertEquals(listOf(2L, 1L, 5L, 3L, 4L), last.items.map { it.id })
    }

    @Test fun collection_view_follows_first_visible() = runBlocking {
        filter.playableOnly.value = false
        val vm = viewModel(BrowseRoute(collectionId = 2))
        vm.await { it.works.isNotEmpty() && it.title.isNotEmpty() }
        val v = withTimeout(5_000) { vm.tvView.first { it?.title == "Alien [1979-]" } }!!
        assertEquals("collection", v.screen)
        assertEquals(2L, v.collectionId)
        assertEquals(listOf(2L), v.items.map { it.id })
    }
}
