package fr.remotecontrol.ui.remote

import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.ui.common.FilePills
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val api = FakeRcApi()
    private val events = MutableSharedFlow<ServerEvent>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val playing: PlayerSnapshotDto = RcJson.decodeFromString(Fixtures.text("player_state.json"))
    private val tracks: MediaTracksDto = RcJson.decodeFromString(Fixtures.text("tracks.json"))
    private val vms = mutableListOf<RemoteViewModel>()

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        scope.cancel()
    }

    /** ViewModel dont l'état est observé (comme par l'écran) dans [on], qui porte aussi le contrôleur. */
    private fun viewModel(on: CoroutineScope = scope): RemoteViewModel {
        val vm = RemoteViewModel(api, RemoteController(api, events, on)).also { vms += it }
        observe(vm, on)
        return vm
    }

    private fun observe(vm: RemoteViewModel, on: CoroutineScope): Job = on.launch { vm.state.collect {} }

    /**
     * Test en temps virtuel : [vt] partage l'ordonnanceur du test (et de Dispatchers.Main). Les ViewModels et
     * [vt] sont arrêtés même si une assertion échoue, sinon le tic d'une seconde ferait tourner runTest sans fin.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun virtualTest(block: suspend TestScope.(vt: CoroutineScope) -> Unit) = runTest {
        val vt = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        try {
            block(vt)
        } finally {
            vms.forEach { it.clearAndJoin() }
            vt.cancel()
        }
    }

    private suspend fun push(s: PlayerSnapshotDto) {
        events.subscriptionCount.first { it > 0 }
        events.emit(ServerEvent.Player(s))
    }

    private fun at(position: Double, paused: Boolean = false) =
        playing.copy(state = playing.state.copy(position = position, paused = paused))

    private fun commands(prefix: String) = api.calls.filter { it.startsWith(prefix) }

    @Test fun maps_snapshot() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        api.tracksResult = ApiResult.Ok(tracks)
        val ui = viewModel().state.value
        assertTrue(ui.running)
        assertEquals("Ava", ui.title)
        assertEquals(1L, ui.workId)
        assertEquals("10:12", ui.positionLabel)
        assertEquals("1:39:00", ui.durationLabel)
        assertEquals(80, ui.volume)
        assertEquals(2, ui.selectedAudio)
        assertEquals(2, ui.audio.size)
        assertTrue(api.calls.contains("tracks(2)"))
    }

    @Test fun shows_poster_and_year_quality_language() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        api.tracksResult = ApiResult.Ok(tracks)
        val asked = mutableListOf<Pair<Long, Long>>()
        val vm = RemoteViewModel(api, RemoteController(api, events, scope)) { work, file ->
            asked += work to file
            flowOf(PlayingInfo(poster = "abc", year = 2017, quality = "FHD"))
        }.also { vms += it }
        observe(vm, scope)
        val ui = withTimeout(5_000) { vm.state.first { it.details.isNotEmpty() } }
        assertEquals("abc", ui.poster)
        assertEquals("2017 · FHD · Français", ui.details)
        assertEquals(listOf(1L to 2L), asked)
    }

    @Test fun language_falls_back_to_file_audio_when_track_unknown() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        // Pistes illisibles : la langue vient des langues audio du fichier, dans le catalogue.
        api.tracksResult = ApiResult.Err(ApiError.Unreachable)
        val vm = RemoteViewModel(api, RemoteController(api, events, scope)) { _, _ ->
            flowOf(PlayingInfo(poster = null, year = 2006, quality = "SD", audioLangs = listOf("en", "fr")))
        }.also { vms += it }
        observe(vm, scope)
        assertEquals("2006 · SD · Anglais", withTimeout(5_000) { vm.state.first { it.details.isNotEmpty() } }.details)
    }

    @Test fun header_pills_of_the_playing_file() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        api.tracksResult = ApiResult.Ok(tracks)
        val info = PlayingInfo(null, 2006, "SD", audioLangs = listOf("en"), subtitleLangs = listOf("fr"), width = 624, height = 352)
        val vm = RemoteViewModel(api, RemoteController(api, events, scope)) { _, _ -> flowOf(info) }.also { vms += it }
        observe(vm, scope)
        val ui = withTimeout(5_000) { vm.state.first { it.pills != null } }
        assertEquals(FilePills("SD", "624\u202F×\u202F352", listOf("Anglais", "ST français")), ui.pills)
    }

    @Test fun skip_and_restart_commands() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        vm.skip(-10)
        vm.skip(60)
        vm.restart()
        assertEquals(listOf("seek(-10.0,true)", "seek(60.0,true)", "seek(0.0,false)"), commands("seek"))
    }

    @Test fun toggle_pause_uses_current_state() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        assertFalse(vm.state.value.paused)
        vm.togglePause()
        assertEquals(listOf("pause(true)"), commands("pause"))
    }

    @Test fun drag_ignores_server_positions_and_seeks_once() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        vm.onSeekDrag(3000.0)
        push(at(620.0))
        assertEquals(3000.0, vm.state.value.position, 0.0)
        vm.onSeekDrag(3100.0)
        assertEquals(3100.0, vm.state.value.position, 0.0)
        assertEquals("51:40", vm.state.value.positionLabel)
        assertTrue(commands("seek").isEmpty())
        vm.onSeekRelease()
        assertEquals(listOf("seek(3100.0,false)"), commands("seek"))
        push(at(3101.0))
        assertEquals(3101.0, vm.state.value.position, 0.0)
    }

    @Test fun volume_sent_on_release_only() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        vm.onVolumeDrag(40)
        vm.onVolumeDrag(50)
        vm.onVolumeDrag(55)
        assertEquals(55, vm.state.value.volume)
        assertTrue(commands("volume").isEmpty())
        vm.onVolumeRelease()
        assertEquals(listOf("volume(55)"), commands("volume"))
    }

    private fun withVolume(level: Int) = playing.copy(state = playing.state.copy(volume = level))

    @Test fun volume_step_moves_slider_immediately() = runBlocking {
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        val vm = viewModel()
        vm.volumeStep(true)
        assertEquals(55, vm.state.value.volume)
    }

    @Test fun volume_step_clamps() = runBlocking {
        api.playerStateResult = ApiResult.Ok(withVolume(98))
        val vm = viewModel()
        vm.volumeStep(true)
        // 98 est à la tolérance de 100 : le curseur est déjà relâché, la cible envoyée le prouve.
        assertEquals(listOf("volume(100)"), commands("volume"))
    }

    @Test fun volume_step_clamps_at_zero() = runBlocking {
        api.playerStateResult = ApiResult.Ok(withVolume(3))
        val vm = viewModel()
        vm.volumeStep(false)
        assertEquals(listOf("volume(0)"), commands("volume"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun volume_keys_coalesced_last_value_wins() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        val vm = RemoteViewModel(api, RemoteController(api, events, vt), volumeSendIntervalMs = 200L).also { vms += it }
        observe(vm, vt)
        repeat(6) { vm.volumeStep(true) }
        advanceTimeBy(50)
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(80)"), commands("volume"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun volume_sends_are_serialised_on_slow_network() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        val gate = CompletableDeferred<Unit>()
        api.volumeGate = gate
        val vm = RemoteViewModel(api, RemoteController(api, events, vt), volumeSendIntervalMs = 200L).also { vms += it }
        observe(vm, vt)
        vm.volumeStep(true)
        advanceTimeBy(50)
        repeat(3) { vm.volumeStep(true) }
        advanceTimeBy(1_000)
        assertEquals(listOf("volume(55)"), commands("volume")) // la réponse n'est pas venue : pas de second envoi
        gate.complete(Unit)
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(70)"), commands("volume"))
    }

    @Test fun volume_key_failure_shows_message() = runBlocking {
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        api.volumeResult = ApiResult.Err(ApiError.Unreachable)
        val vm = viewModel()
        vm.volumeStep(true)
        assertEquals("Serveur injoignable", withTimeout(5_000) { vm.state.first { it.message != null } }.message)
        assertEquals(50, withTimeout(5_000) { vm.state.first { it.volume == 50 } }.volume)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun volume_same_level_retargeted_is_still_sent() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        val vm = RemoteViewModel(api, RemoteController(api, events, vt), volumeSendIntervalMs = 250L).also { vms += it }
        observe(vm, vt)
        vm.volumeStep(true)
        advanceTimeBy(10)
        push(withVolume(55))
        advanceTimeBy(300) // envoi de 55 fini, tenue relâchée, cible effacée
        // Le serveur redescend à 50 ; + puis − puis + dans l'intervalle : 55, 50, 55.
        push(withVolume(50))
        vm.volumeStep(true)
        vm.volumeStep(false)
        vm.volumeStep(true)
        advanceTimeBy(10)
        assertEquals(listOf("volume(55)", "volume(55)"), commands("volume"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun volume_same_level_after_failure_returns_slider() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(withVolume(50))
        api.volumeResult = ApiResult.Err(ApiError.Unreachable)
        val vm = RemoteViewModel(api, RemoteController(api, events, vt), volumeSendIntervalMs = 250L).also { vms += it }
        observe(vm, vt)
        vm.volumeStep(true)
        advanceTimeBy(300)
        assertEquals(50, vm.state.value.volume)
        vm.volumeStep(true) // même cible 55 que l'envoi échoué
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(55)"), commands("volume"))
        assertEquals(50, vm.state.value.volume)
    }

    @Test fun idle_error_message() = runBlocking {
        api.stopResult = ApiResult.Err(ApiError.Server("idle", null))
        val vm = viewModel()
        vm.stop()
        assertEquals("Aucune lecture en cours", vm.state.value.message)
        vm.clearMessage()
        assertEquals(null, vm.state.value.message)
    }

    @Test fun not_running_shows_idle() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        assertEquals("Ava", vm.state.value.title)
        push(PlayerSnapshotDto(PlayerStateDto(running = false), current = null))
        assertFalse(vm.state.value.running)
        assertEquals("Aucune lecture en cours", vm.state.value.title)
    }

    @Test fun end_of_playback_keeps_link_to_sheet() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        push(PlayerSnapshotDto(PlayerStateDto(running = false), current = null))
        val ui = vm.state.value
        assertTrue(ui.finished)
        assertEquals("Ava", ui.finishedTitle)
        assertEquals(1L, ui.workId)
    }

    @Test fun nothing_seen_is_not_finished() = runBlocking {
        val ui = viewModel().state.value
        assertFalse(ui.running)
        assertFalse(ui.finished)
        assertEquals(null, ui.workId)
    }

    @Test fun tracks_loaded_once_per_media() = runBlocking {
        val vm = viewModel()
        push(at(700.0))
        push(at(701.0))
        assertEquals(1, api.calls.count { it == "tracks(2)" })
        assertEquals(701.0, vm.state.value.position, 0.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun position_interpolated_while_playing() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel(vt)
        advanceTimeBy(1_001)
        assertEquals(613.5, vm.state.value.position, 0.0)
        assertEquals("10:13", vm.state.value.positionLabel)
        push(at(800.0, paused = true))
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(800.0, vm.state.value.position, 0.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun interpolation_capped_at_duration() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(at(5_939.5))
        val vm = viewModel(vt)
        advanceTimeBy(3_001)
        assertEquals(5_940.0, vm.state.value.position, 0.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun no_tick_when_ended_or_stopped() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(playing.copy(state = playing.state.copy(ended = true)))
        val vm = viewModel(vt)
        advanceTimeBy(3_001)
        assertEquals(612.5, vm.state.value.position, 0.0)
        push(PlayerSnapshotDto(PlayerStateDto(position = 42.0, running = false)))
        advanceTimeBy(3_001)
        assertEquals(42.0, vm.state.value.position, 0.0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun seek_hold_ignores_stale_events_until_timeout() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(at(600.0, paused = true))
        val vm = viewModel(vt)
        vm.onSeekDrag(3_000.0)
        vm.onSeekRelease()
        push(at(610.0, paused = true)) // événement antérieur à la commande
        advanceTimeBy(1_000)
        assertEquals(3_000.0, vm.state.value.position, 0.0)
        advanceTimeBy(1_001)
        assertEquals(610.0, vm.state.value.position, 0.0)
    }

    @Test fun volume_hold_released_within_tolerance() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        vm.onVolumeDrag(55)
        vm.onVolumeRelease()
        push(playing.copy(state = playing.state.copy(volume = 70))) // antérieur à la commande
        assertEquals(55, vm.state.value.volume)
        push(playing.copy(state = playing.state.copy(volume = 54)))
        assertEquals(54, vm.state.value.volume)
    }

    @Test fun tracks_failure_not_retried_per_event() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Unreachable)
        val vm = viewModel()
        push(at(700.0))
        assertEquals("Serveur injoignable", vm.state.value.message)
        vm.clearMessage()
        push(at(701.0))
        assertEquals(1, api.calls.count { it == "tracks(2)" })
        assertEquals(null, vm.state.value.message)
        // Autre fichier, puis retour : nouvel essai.
        push(playing.copy(current = playing.current!!.copy(mediaFileId = 3)))
        api.tracksResult = ApiResult.Ok(tracks)
        push(at(702.0))
        assertEquals(2, api.calls.count { it == "tracks(2)" })
        assertEquals(2, vm.state.value.audio.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun stops_working_when_screen_leaves() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = RemoteViewModel(api, RemoteController(api, events, vt)).also { vms += it }
        val screen = observe(vm, vt)
        advanceTimeBy(2_001)
        assertEquals(614.5, vm.state.value.position, 0.0)
        screen.cancel()
        advanceTimeBy(5_001)
        val frozen = vm.state.value.position
        advanceTimeBy(5_000)
        assertEquals(frozen, vm.state.value.position, 0.0)
        assertEquals(0, events.subscriptionCount.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reopening_later_does_not_replay_past_playback() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(playing)
        val controller = RemoteController(api, events, vt)
        val first = RemoteViewModel(api, controller).also { vms += it }
        val screen = observe(first, vt)
        assertTrue(first.state.value.running)
        screen.cancel()
        first.clearAndJoin()
        advanceTimeBy(5_001)
        // Plus rien ne joue, et le serveur ne répond plus.
        api.playerStateResult = ApiResult.Err(ApiError.Unreachable)
        val second = RemoteViewModel(api, controller).also { vms += it }
        observe(second, vt)
        val ui = second.state.value
        assertFalse(ui.running)
        assertFalse(ui.finished)
        assertEquals(null, ui.workId)
    }

    /** Retour après plus de 5 s : tant que le serveur n'a rien redit, l'écran charge, il n'annonce pas la fin. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unknown_state_on_return_is_loading_not_finished() = virtualTest { vt ->
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = RemoteViewModel(api, RemoteController(api, events, vt)).also { vms += it }
        val screen = observe(vm, vt)
        assertTrue(vm.state.value.running)
        assertFalse(vm.state.value.loading)
        screen.cancel()
        advanceTimeBy(10_001)
        api.playerStateResult = ApiResult.Err(ApiError.Unreachable)
        observe(vm, vt)
        val ui = vm.state.value
        assertTrue(ui.loading)
        assertFalse(ui.finished)
        assertFalse(ui.interrupted)
        // Le serveur répond ensuite : plus rien ne joue, c'est alors la fin de lecture.
        push(PlayerSnapshotDto(PlayerStateDto(running = false), current = null))
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.finished)
    }
}
