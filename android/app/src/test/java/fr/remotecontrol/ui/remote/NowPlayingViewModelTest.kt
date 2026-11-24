package fr.remotecontrol.ui.remote

import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NowPlayingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val api = FakeRcApi()
    private val events = MutableSharedFlow<ServerEvent>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val playing: PlayerSnapshotDto = RcJson.decodeFromString(Fixtures.text("player_state.json"))
    private lateinit var vm: NowPlayingViewModel

    @After fun tearDown() = runBlocking {
        vm.clearAndJoin()
        scope.cancel()
    }

    private fun viewModel(): NowPlayingViewModel {
        vm = NowPlayingViewModel(api, RemoteController(api, events, scope)) { _, _ ->
            flowOf(PlayingInfo(poster = "abc", year = 2017, quality = "FHD", title = "Ava"))
        }
        scope.launch { vm.state.collect {} }
        return vm
    }

    private suspend fun NowPlayingViewModel.await(predicate: (NowPlayingUi) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    @Test fun shows_playback_with_local_poster() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val ui = viewModel().await { it.player?.poster != null }
        assertEquals("Ava", ui.player?.title)
        assertEquals("abc", ui.player?.poster)
    }

    @Test fun toggle_pause_sends_command_and_reports_failure() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val vm = viewModel()
        vm.await { it.player != null }
        api.pauseResult = ApiResult.Err(ApiError.Unreachable)
        vm.togglePause()
        assertEquals("Serveur injoignable", vm.await { it.message != null }.message)
        assertTrue(api.calls.any { it.startsWith("pause(") })
        vm.clearMessage()
        assertEquals(null, vm.await { it.message == null }.message)
    }
}
