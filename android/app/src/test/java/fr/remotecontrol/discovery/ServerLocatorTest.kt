package fr.remotecontrol.discovery

import fr.remotecontrol.data.META_SERVER_HOST
import fr.remotecontrol.data.META_SERVER_MANUAL
import fr.remotecontrol.data.META_SERVER_PORT
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.ServerProbe
import fr.remotecontrol.testing.FakeKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLocatorTest {
    private val alive = mutableSetOf<String>()
    private val noPlayer = mutableSetOf<String>()
    private var probes = 0
    private val probe = ServerProbe { base ->
        probes++
        if (base.host == "10.0.0.99") awaitCancellation() // serveur muet : ne répond jamais
        if (base.host in alive) InfoDto("rc", 1, 1, playerAvailable = base.host !in noPlayer) else null
    }
    private val store = FakeKeyValueStore()
    private var collected = false
    private var found: List<ServerAddress> = emptyList()
    private val discovery = ServerDiscovery {
        flow<ServerAddress> {
            collected = true
            found.forEach { emit(it) }
            awaitCancellation()
        }
    }
    private fun locator() = ServerLocator(store, probe, discovery)

    private fun remember(host: String, manual: Boolean) {
        store.map[META_SERVER_HOST] = host
        store.map[META_SERVER_PORT] = "8765"
        store.map[META_SERVER_MANUAL] = manual.toString()
    }

    @Test fun stored_address_used_when_alive() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.locate()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
        assertFalse(collected)
    }

    @Test fun player_available_follows_each_probe() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"; noPlayer += "10.0.0.1"
        val l = locator()
        assertTrue(l.playerAvailable.value) // inconnu : rien n'est signalé
        l.locate()
        assertFalse(l.playerAvailable.value)
        noPlayer.clear()
        l.refresh() // même cadence que la sonde de joignabilité
        assertTrue(l.playerAvailable.value)
    }

    @Test fun player_available_reset_when_server_lost() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"; noPlayer += "10.0.0.1"
        val l = locator()
        l.locate()
        assertFalse(l.playerAvailable.value)
        alive.clear()
        l.refresh()
        assertEquals(ServerState.NotFound, l.state.value)
        assertTrue(l.playerAvailable.value)
    }

    @Test fun locate_is_noop_when_connected() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.locate()
        val before = probes
        l.locate()
        assertEquals(before, probes)
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
    }

    @Test fun forced_locate_reprobes_when_connected() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.locate()
        alive.clear()
        l.locate(force = true)
        assertEquals(ServerState.NotFound, l.state.value)
    }

    @Test fun stored_address_fails_then_nsd_finds_new_one() = runTest {
        remember("10.0.0.1", false)
        found = listOf(ServerAddress("10.0.0.2")); alive += "10.0.0.2"
        val l = locator()
        l.locate()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.2"), false), l.state.value)
        assertEquals("10.0.0.2", store.map[META_SERVER_HOST])
    }

    @Test fun nsd_skips_non_responding_candidates() = runTest {
        found = listOf(ServerAddress("10.0.0.5"), ServerAddress("10.0.0.6")); alive += "10.0.0.6"
        val l = locator()
        l.locate()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.6"), false), l.state.value)
    }

    @Test fun nsd_bad_host_is_skipped() = runTest {
        found = listOf(ServerAddress("fe80::1%wlan0"), ServerAddress("a#b"), ServerAddress("10.0.0.7"))
        alive += "10.0.0.7"
        val l = locator()
        l.locate()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.7"), false), l.state.value)
    }

    @Test fun bad_stored_host_does_not_crash() = runTest {
        remember("a#b", false)
        val l = locator()
        l.locate()
        assertEquals(ServerState.NotFound, l.state.value)
    }

    @Test fun not_found_after_timeout() = runTest {
        val l = locator()
        val job = launch { l.locate() }
        runCurrent()
        assertEquals(ServerState.Searching, l.state.value)
        advanceTimeBy(5_001)
        runCurrent()
        job.join()
        assertEquals(ServerState.NotFound, l.state.value)
        assertNull(l.currentBaseUrl())
    }

    @Test fun manual_rejected_when_unreachable() = runTest {
        val l = locator()
        assertFalse(l.setManual(ServerAddress("10.0.0.9")))
        assertEquals(ServerState.Searching, l.state.value)
        assertNull(store.map[META_SERVER_HOST])
    }

    @Test fun manual_kept_flag() = runTest {
        alive += "10.0.0.9"
        val l = locator()
        assertEquals(true, l.setManual(ServerAddress("10.0.0.9")))
        l.locate()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.9"), true), l.state.value)
    }

    @Test fun refresh_keeps_connected_server_with_one_probe() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.locate()
        val before = probes
        l.refresh()
        assertEquals(before + 1, probes)
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
        assertFalse(collected)
    }

    @Test fun refresh_relocates_when_connected_server_is_gone() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.locate()
        alive.clear()
        found = listOf(ServerAddress("10.0.0.2")); alive += "10.0.0.2"
        val before = probes
        l.refresh()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.2"), false), l.state.value)
        assertEquals(before + 2, probes) // l'ancienne adresse une seule fois, puis le candidat découvert
    }

    @Test fun refresh_without_server_searches() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val l = locator()
        l.refresh()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
    }

    @Test fun silent_server_times_out_after_three_seconds() = runTest {
        remember("10.0.0.99", false)
        val l = locator()
        val job = launch { l.locate() }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(collected)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(collected) // sonde abandonnée : découverte lancée
        job.join()
        assertEquals(ServerState.NotFound, l.state.value)
    }

    @Test fun monitor_retries_every_20_s_while_not_found() = runTest {
        val l = locator()
        val job = launch { l.monitor(MutableStateFlow(0)) }
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(ServerState.NotFound, l.state.value)
        found = listOf(ServerAddress("10.0.0.3")); alive += "10.0.0.3"
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(ServerState.NotFound, l.state.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.3"), false), l.state.value)
        job.cancel()
    }

    @Test fun monitor_stops_retrying_when_cancelled() = runTest {
        val l = locator()
        val job = launch { l.monitor(MutableStateFlow(0)) }
        advanceTimeBy(5_001)
        runCurrent()
        job.cancel()
        val before = probes
        found = listOf(ServerAddress("10.0.0.3")); alive += "10.0.0.3"
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(before, probes)
        assertEquals(ServerState.NotFound, l.state.value)
    }

    @Test fun monitor_reprobes_after_three_websocket_failures() = runTest {
        remember("10.0.0.1", false); alive += "10.0.0.1"
        val failures = MutableStateFlow(0)
        val l = locator()
        val job = launch { l.monitor(failures) }
        runCurrent()
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
        val before = probes
        alive.clear()
        failures.value = 1; runCurrent()
        failures.value = 2; runCurrent()
        assertEquals(before, probes)
        assertEquals(ServerState.Connected(ServerAddress("10.0.0.1"), false), l.state.value)
        failures.value = 3; runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(ServerState.NotFound, l.state.value)
        assertNull(l.currentBaseUrl())
        job.cancel()
    }
}
