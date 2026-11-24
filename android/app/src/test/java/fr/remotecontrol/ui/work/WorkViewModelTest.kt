package fr.remotecontrol.ui.work

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.SheetAction
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.collectNow
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.NavEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import kotlinx.coroutines.flow.MutableStateFlow
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import fr.remotecontrol.player.TvStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private val serverEvents = MutableSharedFlow<ServerEvent>()
    private val tvStatus = TvStatus(serverEvents)
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val vms = mutableListOf<WorkViewModel>()

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun viewModel(id: Long) =
        WorkViewModel(id, db.catalogDao(), api, PlayLauncher(api), tvStatus).also { vms += it }

    // Room répond hors du dispatcher de test : on attend l'état voulu.
    private suspend fun WorkViewModel.await(predicate: (WorkUi) -> Boolean = { true }) =
        withTimeout(5_000) { state.filterNotNull().first(predicate) }

    private val live = MutableStateFlow<PlayerSnapshotDto?>(null)
    private val playerMissing = MutableStateFlow(false)

    private fun liveViewModel(id: Long) =
        WorkViewModel(id, db.catalogDao(), api, PlayLauncher(api), tvStatus, live, playerMissing).also { vms += it }

    private fun playing(current: CurrentDto, ended: Boolean = false) =
        PlayerSnapshotDto(PlayerStateDto(running = true, ended = ended), current)

    @Test fun sheet_of_the_work_playing_on_tv_opens_the_remote() = runBlocking {
        val vm = liveViewModel(1)
        assertEquals(SheetAction.Play, vm.await().action)
        live.value = playing(CurrentDto("work", 1, 1, 1, title = "Film"))
        assertTrue(vm.await { it.action == SheetAction.OpenRemote }.playingHere)
        live.value = playing(CurrentDto("work", 1, 1, 1), ended = true)
        vm.await { it.action == SheetAction.Play }
        // Une autre œuvre en lecture : cette fiche garde « Lire ».
        live.value = playing(CurrentDto("work", 2, 3, 2))
        vm.await { it.action == SheetAction.Play && !it.playingHere }
        Unit
    }

    @Test fun series_sheet_follows_its_episode_playing() = runBlocking {
        val vm = liveViewModel(5)
        live.value = playing(CurrentDto("episode", 2, 6, 5, seasonId = 1))
        vm.await { it.playingHere }
        Unit
    }

    @Test fun player_missing_disables_play() = runBlocking {
        val vm = liveViewModel(1)
        playerMissing.value = true
        vm.await { it.action == SheetAction.PlayerMissing }
        playerMissing.value = false
        vm.await { it.action == SheetAction.Play }
        Unit
    }

    @Test fun retry_server_searches_again() = runBlocking {
        val searched = CompletableDeferred<Unit>()
        val vm = WorkViewModel(1, db.catalogDao(), api, PlayLauncher(api), tvStatus) { searched.complete(Unit) }.also { vms += it }
        vm.retryServer()
        withTimeout(5_000) { searched.await() }
    }

    @Test fun tv_label_follows_tv_and_resume_resends_after_drift() = runBlocking {
        val watcher = launch(Dispatchers.Main) { tvStatus.run() }
        while (serverEvents.subscriptionCount.value == 0) yield()
        val vm = viewModel(1)
        vm.onScreenResumed() // ouverture : déjà envoyée
        vm.await { it.onTv }
        serverEvents.emit(ServerEvent.Tv(TvStateDto("home")))
        vm.await { !it.onTv }
        serverEvents.emit(ServerEvent.Player(PlayerSnapshotDto()))
        vm.onScreenResumed()
        vm.await { it.onTv }
        assertEquals(2, api.calls.count { it == "tvShow(work,1)" })
        serverEvents.emit(ServerEvent.Tv(TvStateDto("work", workId = 1)))
        vm.onScreenResumed()
        assertEquals(2, api.calls.count { it == "tvShow(work,1)" })
        watcher.cancel()
    }

    @Test fun init_sends_tv_show_once() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.await()
        assertEquals(1, api.calls.count { it == "tvShow(work,1)" })
    }

    @Test fun tv_show_failure_is_silent() = runBlocking {
        api.tvShowResult = ApiResult.Err(ApiError.Unreachable)
        val ui = viewModel(1).await()
        assertNull(ui.message)
        assertEquals("Ava", ui.work.titleFr)
        assertFalse(ui.onTv)
    }

    @Test fun tv_show_not_shown_during_playback_is_not_an_error() = runBlocking {
        api.tvShowResult = ApiResult.Ok(false)
        val ui = viewModel(1).await()
        assertNull(ui.message)
        assertFalse(ui.onTv)
    }

    @Test fun tv_shown_is_flagged() = runBlocking {
        assertTrue(viewModel(1).await { it.onTv }.onTv)
    }

    @Test fun ava_state() = runBlocking {
        val ui = viewModel(1).await()
        assertEquals("Films", ui.typeLabel)
        assertEquals("1 h 39", ui.durationLabel)
        assertEquals("Reprendre à 10 min", ui.resumeLabel)
        assertTrue(ui.canPlay)
        assertNull(ui.availabilityLabel)
        assertEquals(listOf("FHD · 3.8G", "HD · 1.6G"), ui.versions.map { it.label })
        assertEquals(listOf("Les 1000 plus grands films de tous les temps [2020]"), ui.collections.map { it.name })
        assertEquals(listOf("2017"), ui.collections.map { it.rank })
        // Reprise sur la version HD de la progression ; pas de choix de sous-titres mémorisé.
        assertEquals("HD", ui.choiceSummary)
    }

    @Test fun theatre_absent_disk() = runBlocking {
        val ui = viewModel(4).await()
        assertEquals("Disque « Théâtre » non branché", ui.availabilityLabel)
        assertFalse(ui.canPlay)
        assertEquals(listOf(false), ui.versions.map { it.present })
        assertEquals("Disque « Théâtre » non branché", ui.versions.single().absentLabel)
    }

    @Test fun alien_not_digitized() = runBlocking {
        val ui = viewModel(2).await()
        assertEquals("Non numérisé", ui.availabilityLabel)
        assertFalse(ui.canPlay)
        assertNull(ui.resumeLabel)
    }

    @Test fun play_success_navigates_to_remote() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.playFromStart()
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "start")})"))
        nav.stop()
    }

    @Test fun resume_sends_resume_without_tracks() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.resume()
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "resume")})"))
        nav.stop()
    }

    @Test fun double_tap_launches_once() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        api.playGate = gate
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.playFromStart()
        vm.playFromStart()
        vm.resume()
        assertTrue(vm.await { it.launching }.launching)
        assertEquals(1, api.calls.count { it.startsWith("play(") })
        gate.complete(Unit)
        assertFalse(vm.await { !it.launching }.launching)
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        nav.stop()
    }

    @Test fun nav_event_without_listener_is_not_replayed() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.playFromStart() // l'écran n'écoute pas (parti ailleurs) : l'événement est perdu
        val nav = collectNow(vm.events)
        assertEquals(emptyList<NavEvent>(), nav.items)
        assertEquals(1, api.calls.count { it.startsWith("play(") })
        nav.stop()
    }

    @Test fun play_error_shows_message() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Server("volume_absent", "DISK1"))
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.playFromStart()
        assertEquals("Disque « DISK1 » non branché", vm.await { it.message != null }.message)
        assertEquals(emptyList<NavEvent>(), nav.items)
        vm.clearMessage()
        assertNull(vm.await { it.message == null }.message)
        nav.stop()
    }

    @Test fun new_launch_clears_previous_message() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Unreachable)
        val vm = viewModel(1)
        vm.await()
        vm.playFromStart()
        vm.await { it.message != null }
        val gate = CompletableDeferred<Unit>()
        api.playGate = gate
        vm.playFromStart()
        assertNull(vm.await { it.launching }.message)
        gate.complete(Unit)
        assertFalse(vm.await { !it.launching }.launching)
    }

    @Test fun unknown_work_is_missing() = runBlocking {
        val vm = viewModel(99)
        assertTrue(withTimeout(5_000) { vm.missing.first { it } })
        assertNull(vm.state.value)
    }

    @Test fun series_lists_seasons_no_play() = runBlocking {
        val ui = viewModel(5).await { it.seasons.isNotEmpty() }
        assertEquals(listOf(1L, 2L), ui.seasons.map { it.id })
        assertFalse(ui.canPlay)
        assertNull(ui.availabilityLabel)
    }
}
