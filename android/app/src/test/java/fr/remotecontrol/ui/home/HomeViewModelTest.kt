package fr.remotecontrol.ui.home

import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.ResumeRow
import fr.remotecontrol.data.sync.SyncProgress
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.discovery.ServerAddress
import fr.remotecontrol.discovery.ServerDiscovery
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerProbe
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.FakeKeyValueStore
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val vms = mutableListOf<HomeViewModel>()

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private val api = FakeRcApi()
    private val empty = SyncDto(revision = 1, full = true)
    private val locator = ServerLocator(FakeKeyValueStore(), ServerProbe { InfoDto("rc", 1, 1) }, ServerDiscovery { emptyFlow() })

    private fun viewModel(repo: SyncRepository = SyncRepository(db, api), progress: Flow<SyncProgress> = repo.progress) = HomeViewModel(
        db.catalogDao(),
        CatalogFilter(),
        locator,
        repo,
        progress,
    ).also { vms += it }

    // Room exécute ses requêtes hors du dispatcher de test : on attend l'état voulu.
    private suspend fun HomeViewModel.await(predicate: (HomeUi) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    @Test fun resume_lists_episode_then_work() = runBlocking {
        SyncWriter(db).apply(full)
        val vm = viewModel()
        val ui = vm.await { it.resume.size == 2 }
        assertEquals(listOf("Back to Life", "Ava"), ui.resume.map { it.title })
        assertEquals("S1 É2 · reste 19 min", ui.resume[0].label)
        assertEquals("reste 1 h 29", ui.resume[1].label)
    }

    @Test fun home_first_sync_shows_writing_progress() = runBlocking {
        val ui = viewModel(progress = MutableStateFlow(SyncProgress.Writing(1250, 3992))).await { it.syncCount != null }
        assertEquals("1\u202F250 / 3\u202F992 œuvres", ui.syncCount)
        assertEquals("Enregistrement des œuvres…", ui.syncLabel)
        assertEquals(1250f / 3992f, ui.syncFraction!!, 1e-6f)
    }

    @Test fun home_first_sync_download_is_indeterminate() = runBlocking {
        api.syncGate = CompletableDeferred()
        api.syncResponses.addLast(ApiResult.Ok(empty))
        val repo = SyncRepository(db, api)
        val vm = viewModel(repo)
        val sync = launch(Dispatchers.Default) { repo.sync(forceFull = true) }
        val ui = vm.await { it.syncLabel != null }
        assertEquals("Téléchargement du catalogue…", ui.syncLabel)
        assertNull(ui.syncCount)
        assertNull(ui.syncFraction)
        api.syncGate!!.complete(Unit)
        sync.join()
        // Catalogue vide : Téléchargement → Repos, sans étape d'écriture.
        assertNull(vm.await { it.syncLabel == null }.syncFraction)
    }

    @Test fun resume_label_falls_back_without_duration() {
        val row = ResumeRow("work", 1, 1, null, "Ava", null, null, null, 300.0, null, 0.0)
        assertEquals("Reprendre à 5 min", toResumeItem(row).label)
        assertEquals(0f, toResumeItem(row).progress)
    }

    @Test fun banner_follows_server_state() {
        val here = ServerState.Connected(ServerAddress("10.0.0.1"), false)
        assertEquals(HomeBanner.Searching, homeBanner(ServerState.Searching, catalogEmpty = true))
        assertEquals(HomeBanner.NoServer, homeBanner(ServerState.NotFound, catalogEmpty = true))
        assertEquals(HomeBanner.NoServer, homeBanner(ServerState.NotFound, catalogEmpty = false))
        // Connecté, rien ne se synchronise, jamais synchronisé (première synchro en échec) : pas d'attente sans fin.
        assertEquals(HomeBanner.NotSynced, homeBanner(here, catalogEmpty = true))
        assertEquals(HomeBanner.Syncing, homeBanner(here, catalogEmpty = true, syncing = true))
        assertEquals(null, homeBanner(here, catalogEmpty = false))
        assertEquals(null, homeBanner(ServerState.Searching, catalogEmpty = false))
        // Déjà synchronisé et toujours vide : le serveur n'a aucune œuvre.
        assertEquals(HomeBanner.Empty, homeBanner(here, catalogEmpty = true, everSynced = true))
        assertEquals(HomeBanner.Syncing, homeBanner(here, catalogEmpty = true, everSynced = true, syncing = true))
    }

    @Test fun catalog_empty_after_a_sync_offers_resync() = runBlocking {
        api.syncResponses.addLast(ApiResult.Ok(empty))
        val vm = viewModel()
        locator.setManual(ServerAddress("10.0.0.1"))
        vm.resync()
        val ui = vm.await { it.banner == HomeBanner.Empty }
        assertTrue(ui.catalogEmpty)
        assertTrue("sync(null)" in api.calls)
    }

    @Test fun failed_resync_from_home_shows_message() = runBlocking {
        api.syncResponses.addLast(ApiResult.Err(ApiError.Unreachable))
        val vm = viewModel()
        locator.setManual(ServerAddress("10.0.0.1"))
        vm.resync()
        val ui = vm.await { it.message != null }
        assertEquals("Serveur injoignable", ui.message)
        assertEquals(HomeBanner.NotSynced, ui.banner)
        // Synchronisation automatique réussie ensuite (même vide) : le message d'échec disparaît.
        api.syncResponses.addLast(ApiResult.Ok(empty))
        SyncRepository(db, api).sync()
        assertEquals(HomeBanner.Empty, vm.await { it.message == null }.banner)
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
