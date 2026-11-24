package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.collectNow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteControllerTest {
    private val api = FakeRcApi()
    private val events = MutableSharedFlow<ServerEvent>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val playing: PlayerSnapshotDto = RcJson.decodeFromString(Fixtures.text("player_state.json"))

    @After fun tearDown() = scope.cancel()

    private suspend fun emit(e: ServerEvent) {
        events.subscriptionCount.first { it > 0 }
        events.emit(e)
    }

    @Test fun initial_state_from_http_then_events() = runBlocking {
        api.playerStateResult = ApiResult.Ok(playing)
        val controller = RemoteController(api, events, scope)
        val seen = collectNow(controller.snapshot)
        emit(ServerEvent.Player(playing.copy(state = playing.state.copy(position = 700.0))))
        assertEquals(listOf(612.5, 700.0), seen.items.filterNotNull().map { it.state.position })
        seen.stop()
    }

    @Test fun hello_updates_snapshot() = runBlocking {
        api.playerStateResult = ApiResult.Err(ApiError.Unreachable)
        val controller = RemoteController(api, events, scope)
        val seen = collectNow(controller.snapshot)
        assertEquals(null, controller.snapshot.value)
        emit(ServerEvent.Hello(5, TvStateDto("home"), playing))
        assertEquals("Ava", controller.snapshot.value?.current?.title)
        assertEquals(listOf(playing), seen.items.filterNotNull())
        seen.stop()
    }

    @Test fun event_before_http_answer_wins() = runBlocking {
        val stale = playing.copy(state = playing.state.copy(position = 1.0))
        api.playerStateResult = ApiResult.Ok(stale)
        val gate = CompletableDeferred<Unit>()
        val gated = object : RcApi by api {
            override suspend fun playerState(): ApiResult<PlayerSnapshotDto> {
                gate.await()
                return api.playerState()
            }
        }
        val controller = RemoteController(gated, events, scope)
        val seen = collectNow(controller.snapshot)
        emit(ServerEvent.Player(playing))
        gate.complete(Unit)
        assertEquals(listOf(612.5), seen.items.filterNotNull().map { it.state.position })
        seen.stop()
    }
}
