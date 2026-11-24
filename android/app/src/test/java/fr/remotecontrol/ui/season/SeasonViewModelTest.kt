package fr.remotecontrol.ui.season

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.SheetAction
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.collectNow
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.NavEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
class SeasonViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private val serverEvents = MutableSharedFlow<ServerEvent>()
    private val tvStatus = TvStatus(serverEvents)
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val vms = mutableListOf<SeasonViewModel>()

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun viewModel(id: Long) =
        SeasonViewModel(id, db.catalogDao(), api, PlayLauncher(api), tvStatus).also { vms += it }

    private suspend fun SeasonViewModel.await(predicate: (SeasonUi) -> Boolean = { true }) =
        withTimeout(5_000) { state.filterNotNull().first(predicate) }

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
        assertEquals(2, api.calls.count { it == "tvShow(season,1)" })
        serverEvents.emit(ServerEvent.Tv(TvStateDto("season", seasonId = 1)))
        vm.onScreenResumed()
        assertEquals(2, api.calls.count { it == "tvShow(season,1)" })
        watcher.cancel()
    }

    private val live = MutableStateFlow<PlayerSnapshotDto?>(null)
    private val playerMissing = MutableStateFlow(false)

    @Test fun episode_playing_on_tv_opens_the_remote() = runBlocking {
        val vm = SeasonViewModel(1, db.catalogDao(), api, PlayLauncher(api), tvStatus, live, playerMissing).also { vms += it }
        vm.await { it.episodes.size == 2 && it.episodes.all { e -> e.action == SheetAction.Play } }
        live.value = PlayerSnapshotDto(
            PlayerStateDto(running = true),
            CurrentDto("episode", 1, 5, 5, seasonId = 1),
        )
        val ui = vm.await { it.playingHere }
        assertEquals(listOf(SheetAction.OpenRemote, SheetAction.Play), ui.episodes.map { it.action })
        playerMissing.value = true
        val missing = vm.await { it.episodes[1].action == SheetAction.PlayerMissing }
        assertEquals(SheetAction.OpenRemote, missing.episodes[0].action)
        live.value = null
        vm.await { !it.playingHere && it.episodes.all { e -> e.action == SheetAction.PlayerMissing } }
        Unit
    }

    @Test fun init_sends_tv_show_season() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.await()
        assertEquals(1, api.calls.count { it == "tvShow(season,1)" })
    }

    @Test fun highlight_and_status() = runBlocking {
        val ui = viewModel(1).await { it.episodes.size == 2 }
        assertEquals("Back to Life", ui.workTitle)
        val (first, second) = ui.episodes
        assertEquals("S01E01", first.label)
        assertNull(first.status)
        assertFalse(first.highlighted)
        assertFalse(first.canResume)
        assertTrue(second.highlighted)
        assertEquals("Entamé — 5:00", second.status)
        assertTrue(second.canResume)
        assertEquals(listOf(1L, 2L), ui.seasons.map { it.id })
        assertEquals(listOf("FHD"), ui.qualities)
    }

    @Test fun play_episode_resume() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.play(2, StartMode.Resume)
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        assertTrue(api.calls.contains("play(${PlayRequestDto("episode", 2, "resume")})"))
        nav.stop()
    }

    @Test fun double_tap_launches_once() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        api.playGate = gate
        val vm = viewModel(1)
        vm.await()
        val nav = collectNow(vm.events)
        vm.play(2, StartMode.Resume)
        vm.play(1, StartMode.Start)
        assertTrue(vm.await { it.launching }.launching)
        assertEquals(1, api.calls.count { it.startsWith("play(") })
        gate.complete(Unit)
        vm.await { !it.launching }
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        nav.stop()
    }

    @Test fun unknown_season_is_missing() = runBlocking {
        val vm = viewModel(99)
        assertTrue(withTimeout(5_000) { vm.missing.first { it } })
    }

    @Test fun play_error_shows_message() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Server("not_playable", null))
        val vm = viewModel(1)
        vm.await()
        vm.play(1, StartMode.Start)
        assertEquals("Aucun fichier lisible pour cette œuvre", vm.await { it.message != null }.message)
        vm.clearMessage()
        assertNull(vm.await { it.message == null }.message)
    }
}
