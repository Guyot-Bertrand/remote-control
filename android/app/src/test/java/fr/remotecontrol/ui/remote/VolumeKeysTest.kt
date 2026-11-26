package fr.remotecontrol.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import fr.remotecontrol.discovery.ServerAddress
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import fr.remotecontrol.testing.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VolumeKeysTest {
    @get:Rule val main = MainDispatcherRule()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test fun keys_pass_through_without_handler() {
        val keys = VolumeKeys()
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, down))
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, up))
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, down))
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, up))
    }

    @Test fun keys_consumed_with_handler() {
        val keys = VolumeKeys()
        val steps = mutableListOf<Boolean>()
        keys.handler = { steps += it }
        assertTrue(keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, down))
        assertTrue(keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, down)) // répétition d'un appui maintenu
        assertTrue(keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, up))
        assertTrue(keys.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, down))
        assertTrue(keys.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, up))
        assertEquals(listOf(true, true, false), steps)
    }

    @Test fun other_keys_pass_through_even_with_handler() {
        val logs = mutableListOf<String>()
        val keys = VolumeKeys(log = { logs += it })
        val steps = mutableListOf<Boolean>()
        keys.handler = { steps += it }
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_A, down))
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_A, up))
        assertFalse(keys.dispatch(KeyEvent.KEYCODE_BACK, down))
        assertEquals(emptyList<Boolean>(), steps)
        assertEquals(emptyList<String>(), logs) // pas de trace hors touches de volume
    }

    @Test fun each_volume_key_is_logged_with_handler_presence() {
        val logs = mutableListOf<String>()
        val keys = VolumeKeys(log = { logs += it })
        keys.dispatch(KeyEvent.KEYCODE_VOLUME_UP, down)
        keys.handler = {}
        keys.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, up)
        assertEquals(2, logs.size)
        assertTrue(logs[0], logs[0].contains("VOLUME_UP") && logs[0].contains("DOWN") && logs[0].contains("gestionnaire=non"))
        assertTrue(logs[1], logs[1].contains("VOLUME_DOWN") && logs[1].contains("UP") && logs[1].contains("gestionnaire=oui"))
    }

    @Test fun registration_and_removal_are_logged_with_reason() = runTest {
        val logs = mutableListOf<String>()
        val keys = VolumeKeys(log = { logs += it })
        val running = MutableStateFlow(true)
        val job = launch(Dispatchers.Main) { registerVolumeKeysWhileResumed(owner.lifecycle, running, keys) {} }
        owner.registry.currentState = Lifecycle.State.RESUMED
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        running.value = false
        job.cancel()
        assertEquals(4, logs.size)
        assertTrue(logs[0], logs[0].startsWith("inscrit"))
        assertTrue(logs[1], logs[1].startsWith("retiré") && logs[1].contains("cycle de vie"))
        assertTrue(logs[2], logs[2].startsWith("inscrit"))
        assertTrue(logs[3], logs[3].startsWith("retiré") && logs[3].contains("lecture ou connexion"))
    }

    @Test fun active_only_while_playing_and_connected() = runTest {
        val logs = mutableListOf<String>()
        val snapshot = MutableStateFlow<PlayerSnapshotDto?>(null)
        val server = MutableStateFlow<ServerState>(ServerState.Searching)
        val seen = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Main) { volumeKeysActive(snapshot, server) { logs += it }.collect { seen += it } }
        val connected = ServerState.Connected(ServerAddress("192.168.1.2", 8080), manual = false)
        server.value = connected
        snapshot.value = PlayerSnapshotDto(state = PlayerStateDto(running = true))
        snapshot.value = PlayerSnapshotDto(state = PlayerStateDto(running = true, paused = true)) // pause : toujours actif
        server.value = ServerState.Searching
        server.value = connected
        snapshot.value = PlayerSnapshotDto(state = PlayerStateDto(running = true, ended = true))
        job.cancel()
        assertEquals(listOf(false, true, false, true, false), seen)
        assertTrue(logs.joinToString(), logs.any { it.contains("connecté=non") } && logs.any { it.contains("lecture=oui") })
    }

    @Test fun registered_only_while_resumed_and_running() = runTest {
        val keys = VolumeKeys()
        val running = MutableStateFlow(true)
        val job = launch(Dispatchers.Main) { registerVolumeKeysWhileResumed(owner.lifecycle, running, keys) {} }
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertNotNull(keys.handler)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertNull(keys.handler)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertNotNull(keys.handler)
        running.value = false
        assertNull(keys.handler)
        running.value = true
        assertNotNull(keys.handler)
        job.cancel()
        assertNull(keys.handler)
    }

    @Test fun cancel_keeps_a_handler_registered_by_someone_else() = runTest {
        val keys = VolumeKeys()
        val job = launch(Dispatchers.Main) {
            registerVolumeKeysWhileResumed(owner.lifecycle, MutableStateFlow(true), keys) {}
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val other: (Boolean) -> Unit = {}
        keys.handler = other
        job.cancel()
        assertTrue(keys.handler === other)
    }
}
