package fr.remotecontrol.discovery

import fr.remotecontrol.data.META_SERVER_HOST
import fr.remotecontrol.data.META_SERVER_MANUAL
import fr.remotecontrol.data.META_SERVER_PORT
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.ServerProbe
import fr.remotecontrol.testing.FakeKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLocatorTest {
    private val alive = mutableSetOf<String>()
    private val probe = ServerProbe { base -> if (base.host in alive) InfoDto("rc", 1, 1) else null }
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
}
