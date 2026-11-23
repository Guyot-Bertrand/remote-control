package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import fr.remotecontrol.testing.FakeRcApi
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
    private val api = FakeRcApi()
    private val events = MutableSharedFlow<ServerEvent>()
    private val status = TvStatus(events)

    private fun shows() = api.calls.count { it == "tvShow(work,1)" }

    private fun idle(running: Boolean = false) = PlayerSnapshotDto(PlayerStateDto(running = running))

    /** Fiche de l'œuvre 1, avec l'état de la télé suivi comme au premier plan. */
    private fun TestScope.sheet(): TvSheet {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.run() }
        return TvSheet(CoroutineScope(backgroundScope.coroutineContext + dispatcher), api, status, "work", 1)
    }

    @Test fun shown_once_on_open() = runTest {
        val s = sheet()
        assertEquals(1, shows())
        assertTrue(s.onTv.value)
    }

    @Test fun live_tv_events_drive_the_label() = runTest {
        val s = sheet()
        events.emit(ServerEvent.Tv(TvStateDto("season", seasonId = 4)))
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("work", workId = 1)))
        assertTrue(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("error", message = "panne")))
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Tv(TvStateDto("work", workId = 2)))
        assertFalse(s.onTv.value)
    }

    @Test fun offline_open_then_hello_matches() = runTest {
        api.tvShowResult = ApiResult.Err(ApiError.Unreachable)
        val s = sheet()
        assertFalse(s.onTv.value)
        events.emit(ServerEvent.Hello(3, TvStateDto("work", workId = 1), idle()))
        assertTrue(s.onTv.value)
    }

    @Test fun first_resume_does_not_resend() = runTest {
        val s = sheet()
        s.onScreenResumed()
        assertEquals(1, shows())
    }

    @Test fun resume_resends_once_when_tv_drifted_and_idle() = runTest {
        val s = sheet()
        s.onScreenResumed()
        events.emit(ServerEvent.Hello(3, TvStateDto("season", seasonId = 4), idle()))
        assertFalse(s.onTv.value)
        s.onScreenResumed()
        assertEquals(2, shows())
        assertTrue(s.onTv.value)
        // Sans nouvel écart signalé, pas de nouvel envoi au retour suivant tant que la télé suit.
        events.emit(ServerEvent.Tv(TvStateDto("work", workId = 1)))
        s.onScreenResumed()
        assertEquals(2, shows())
    }

    @Test fun resume_does_not_resend_while_playing() = runTest {
        val s = sheet()
        s.onScreenResumed()
        events.emit(ServerEvent.Hello(3, TvStateDto("player"), idle(running = true)))
        s.onScreenResumed()
        assertEquals(1, shows())
        events.emit(ServerEvent.Player(idle(running = false)))
        s.onScreenResumed()
        assertEquals(2, shows())
    }

    @Test fun resume_does_nothing_when_tv_state_unknown() = runTest {
        val s = sheet()
        s.onScreenResumed()
        s.onScreenResumed()
        assertEquals(1, shows())
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
