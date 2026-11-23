package fr.remotecontrol.ui.home

import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.ResumeRow
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.discovery.ServerDiscovery
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerProbe
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.FakeKeyValueStore
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))

    @Before fun setUp() {
        db = testDb()
    }

    @After fun tearDown() = db.close()

    private fun viewModel() = HomeViewModel(
        db.catalogDao(),
        CatalogFilter(),
        ServerLocator(FakeKeyValueStore(), ServerProbe { InfoDto("rc", 1, 1) }, ServerDiscovery { emptyFlow() }),
    )

    // Room exécute ses requêtes hors du dispatcher de test : on attend l'état voulu.
    private suspend fun HomeViewModel.await(predicate: (HomeUi) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    @Test fun resume_lists_episode_then_work() = runBlocking {
        SyncWriter(db).apply(full)
        val vm = viewModel()
        val ui = vm.await { it.resume.size == 2 }
        assertEquals(listOf("Back to Life — S01E02", "Ava"), ui.resume.map { it.title })
        assertEquals("reste 19 min", ui.resume[0].label)
        assertEquals("reste 1 h 29", ui.resume[1].label)
    }

    @Test fun resume_label_falls_back_without_duration() {
        val row = ResumeRow("work", 1, 1, null, "Ava", null, null, null, 300.0, null, 0.0)
        assertEquals("Reprendre à 00:05", toResumeItem(row).label)
        assertEquals(0f, toResumeItem(row).progress)
    }

    @Test fun collections_show_total_and_playable() = runBlocking {
        SyncWriter(db).apply(full)
        val vm = viewModel()
        vm.setPlayableOnly(false)
        val ui = vm.await { !it.playableOnly && it.collections.size == 2 }
        val alien = ui.collections.single { it.id == 2L }
        assertEquals(1, alien.count)
        assertEquals(0, alien.playableCount)
    }

    @Test fun recent_respects_filter() = runBlocking {
        SyncWriter(db).apply(full)
        val vm = viewModel()
        assertEquals(listOf(5L, 3L, 1L), vm.await { it.recent.isNotEmpty() }.recent.map { it.id })
        vm.setPlayableOnly(false)
        val ui = vm.await { !it.playableOnly && it.recent.size == 5 }
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ui.recent.map { it.id })
    }

    @Test fun catalog_empty_flag() = runBlocking {
        val vm = viewModel()
        vm.await { it.catalogEmpty }
        SyncWriter(db).apply(full)
        vm.await { !it.catalogEmpty }
        Unit
    }
}
