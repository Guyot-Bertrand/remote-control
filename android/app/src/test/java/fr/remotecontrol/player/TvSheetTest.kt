package fr.remotecontrol.player

import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import fr.remotecontrol.net.TvViewStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSheetTest {
    private val events = MutableSharedFlow<ServerEvent>()
    private val status = TvStatus(events)

    private fun idle(running: Boolean = false) = PlayerSnapshotDto(PlayerStateDto(running = running))

    /** Fiche [screen] d'identifiant [id], avec l'état de la télé suivi comme au premier plan. */
    private fun TestScope.sheet(screen: String = "work", id: Long = 12): TvSheet {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.run() }
        return TvSheet(CoroutineScope(backgroundScope.coroutineContext + dispatcher), status, screen, id)
    }

    private fun viewState(screen: String, workId: Long? = null, seasonId: Long? = null) =
        TvStateDto("view", view = TvViewStateDto(screen, workId = workId, seasonId = seasonId))

    @Test fun on_tv_follows_view_state() = runTest {
        val s = sheet()
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(viewState("work", workId = 12)))
        assertTrue(s.onTv.value)
        events.emit(ServerEvent.Tv(viewState("work", workId = 13)))
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(viewState("browse")))
        assertFalse(s.onTv.value)
        // La fiche de saison montre aussi l'œuvre 12 : ce n'est pas cette fiche-ci.
        events.emit(ServerEvent.Tv(viewState("season", workId = 12, seasonId = 4)))
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("playing")))
        assertFalse(s.onTv.value)
    }

    @Test fun on_tv_for_season_matches_season_id() = runTest {
        val s = sheet("season", 4)
        events.emit(ServerEvent.Tv(viewState("season", workId = 12, seasonId = 4)))
        assertTrue(s.onTv.value)
        events.emit(ServerEvent.Tv(viewState("season", workId = 12, seasonId = 5)))
        assertFalse(s.onTv.value)
    }

    @Test fun on_tv_supports_iteration1_state() = runTest {
        val s = sheet()
        events.emit(ServerEvent.Tv(TvStateDto("work", workId = 12)))
        assertTrue(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("season", seasonId = 12)))
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("message", message = "panne")))
        assertFalse(s.onTv.value)
    }

    @Test fun state_known_at_opening_counts() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.run() }
        events.emit(ServerEvent.Hello(3, viewState("work", workId = 12), idle()))
        val s = TvSheet(CoroutineScope(backgroundScope.coroutineContext + dispatcher), status, "work", 12)
        assertTrue(s.onTv.value)
    }

    @Test fun offline_is_not_on_tv() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = launch(dispatcher) { status.run() }
        val s = TvSheet(CoroutineScope(backgroundScope.coroutineContext + dispatcher), status, "work", 12)
        events.emit(ServerEvent.Tv(viewState("work", workId = 12)))
        assertTrue(s.onTv.value)
        job.cancel()
        assertFalse(s.onTv.value)
    }

    @Test fun status_forgotten_when_stopped() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = launch(dispatcher) { status.run() }
        events.emit(ServerEvent.Hello(3, TvStateDto("home"), idle()))
        assertEquals(TvStateDto("home"), status.tv.value)
        job.cancel()
        assertNull(status.tv.value)
        assertNull(status.playing.value)
    }
}
