package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeStepperTest {
    private val api = FakeRcApi()
    private val events = MutableSharedFlow<ServerEvent>()
    private val playing: PlayerSnapshotDto = RcJson.decodeFromString(Fixtures.text("player_state.json"))

    // L'état ne vient que des événements poussés par chaque test.
    init { api.playerStateResult = ApiResult.Err(ApiError.Unreachable) }

    private fun withVolume(level: Int) = playing.copy(state = playing.state.copy(volume = level))

    private fun commands() = api.calls.filter { it.startsWith("volume") }

    private suspend fun push(s: PlayerSnapshotDto) {
        events.subscriptionCount.first { it > 0 }
        events.emit(ServerEvent.Player(s))
    }

    /**
     * Temps virtuel : [vt] porte le contrôleur (observé, comme par l'activité) et le stepper ; il est arrêté
     * même si une assertion échoue.
     */
    private fun stepperTest(intervalMs: Long = 250L, block: suspend TestScope.(VolumeStepper) -> Unit) = runTest {
        val vt = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        try {
            val controller = RemoteController(api, events, vt)
            vt.launch { controller.snapshot.collect {} }
            block(VolumeStepper(api, controller.snapshot, vt, sendIntervalMs = intervalMs))
        } finally {
            vt.cancel()
        }
    }

    @Test fun step_moves_pending_immediately() = stepperTest { stepper ->
        push(withVolume(50))
        stepper.step(true)
        assertEquals(55, stepper.pending.value)
        stepper.step(true)
        assertEquals(60, stepper.pending.value)
    }

    @Test fun step_clamps_at_100() = stepperTest { stepper ->
        push(withVolume(98))
        stepper.step(true)
        advanceTimeBy(10)
        assertEquals(listOf("volume(100)"), commands())
    }

    @Test fun step_clamps_at_0() = stepperTest { stepper ->
        push(withVolume(3))
        stepper.step(false)
        advanceTimeBy(10)
        assertEquals(listOf("volume(0)"), commands())
    }

    @Test fun step_without_known_state_does_nothing() = stepperTest { stepper ->
        stepper.step(true) // rien poussé : état inconnu
        advanceTimeBy(300)
        assertNull(stepper.pending.value)
        assertEquals(emptyList<String>(), commands())
    }

    @Test fun coalesced_last_value_wins() = stepperTest(intervalMs = 200L) { stepper ->
        push(withVolume(50))
        repeat(6) { stepper.step(true) }
        advanceTimeBy(50)
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(80)"), commands())
    }

    @Test fun sends_are_serialised_on_slow_network() = stepperTest(intervalMs = 200L) { stepper ->
        push(withVolume(50))
        val gate = CompletableDeferred<Unit>()
        api.volumeGate = gate
        stepper.step(true)
        advanceTimeBy(50)
        repeat(3) { stepper.step(true) }
        advanceTimeBy(1_000)
        assertEquals(listOf("volume(55)"), commands()) // la réponse n'est pas venue : pas de second envoi
        gate.complete(Unit)
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(70)"), commands())
    }

    @Test fun pending_held_until_server_reaches_it_or_timeout() = stepperTest { stepper ->
        push(withVolume(50))
        stepper.step(true)
        advanceTimeBy(10)
        push(withVolume(70)) // antérieur à la commande
        assertEquals(55, stepper.pending.value)
        push(withVolume(54)) // à la tolérance près
        assertNull(stepper.pending.value)
        stepper.step(true) // 54 + 5
        advanceTimeBy(1_999)
        assertEquals(59, stepper.pending.value)
        advanceTimeBy(300)
        assertNull(stepper.pending.value)
    }

    @Test fun failure_releases_pending_and_is_emitted() = stepperTest { stepper ->
        push(withVolume(50))
        api.volumeResult = ApiResult.Err(ApiError.Unreachable)
        val failures = mutableListOf<ApiError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stepper.failures.collect { failures += it } }
        stepper.step(true)
        advanceTimeBy(10)
        assertNull(stepper.pending.value)
        assertEquals(listOf<ApiError>(ApiError.Unreachable), failures)
        advanceTimeBy(300)
        stepper.step(true) // même cible 55 que l'envoi échoué : renvoyée
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(55)"), commands())
        assertNull(stepper.pending.value)
    }

    @Test fun same_level_retargeted_after_interval_is_still_sent() = stepperTest { stepper ->
        push(withVolume(50))
        stepper.step(true)
        advanceTimeBy(10)
        push(withVolume(55))
        advanceTimeBy(300) // envoi de 55 fini, tenue relâchée, cible effacée
        push(withVolume(50))
        stepper.step(true)
        stepper.step(false)
        stepper.step(true)
        advanceTimeBy(10)
        assertEquals(listOf("volume(55)", "volume(55)"), commands())
    }

    /** Régression : sans numéro d'appui, la cible égale au dernier envoi serait effacée à la fin du délai. */
    @Test fun same_level_retargeted_during_interval_is_still_sent() = stepperTest { stepper ->
        push(withVolume(50))
        stepper.step(true) // 55, envoyé tout de suite
        advanceTimeBy(10)
        push(withVolume(55)) // tenue relâchée ; le délai de 250 ms qui suit l'envoi court encore
        assertNull(stepper.pending.value)
        stepper.step(false) // 50
        stepper.step(true) // 55 : même valeur que l'envoi précédent
        advanceTimeBy(300)
        assertEquals(listOf("volume(55)", "volume(55)"), commands())
    }
}
