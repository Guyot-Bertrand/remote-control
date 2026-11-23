package fr.remotecontrol.ui.remote

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    private fun viewModel() = RemoteViewModel(api, RemoteController(api, events, scope)).also { vms += it }

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
    @Test fun position_interpolated_while_playing() = runTest {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        advanceTimeBy(1_001)
        assertEquals(613.5, vm.state.value.position, 0.0)
        assertEquals("10:13", vm.state.value.positionLabel)
        push(at(800.0, paused = true))
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(800.0, vm.state.value.position, 0.0)
        vm.clearAndJoin()
    }
}
