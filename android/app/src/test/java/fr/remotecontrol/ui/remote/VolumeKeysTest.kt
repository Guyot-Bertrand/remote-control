package fr.remotecontrol.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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

    @Test fun keys_pass_through_without_handler() {
        val keys = VolumeKeys()
        assertFalse(keys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(keys.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun keys_consumed_with_handler() {
        val keys = VolumeKeys()
        val steps = mutableListOf<Boolean>()
        keys.handler = { steps += it }
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(listOf(true, false), steps)
        assertTrue(keys.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(2, steps.size)
        assertFalse(keys.onKeyDown(KeyEvent.KEYCODE_A))
        assertFalse(keys.onKeyUp(KeyEvent.KEYCODE_A))
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
        job.cancel()
    }
}
