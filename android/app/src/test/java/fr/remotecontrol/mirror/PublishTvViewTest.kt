package fr.remotecontrol.mirror

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import fr.remotecontrol.net.TvViewDto
import fr.remotecontrol.testing.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PublishTvViewTest {
    @get:Rule val main = MainDispatcherRule()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun publishes_only_while_resumed() = runTest {
        val view = MutableStateFlow<TvViewDto?>(TvViewDto(screen = "home"))
        val published = mutableListOf<TvViewDto>()
        val job = launch(Dispatchers.Main) { publishWhileResumed(owner.lifecycle, view) { published += it } }
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(emptyList<TvViewDto>(), published)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("home"), published.map { it.screen })
        view.value = TvViewDto(screen = "search")
        assertEquals(listOf("home", "search"), published.map { it.screen })
        // En pause (une autre destination au-dessus, ou l'appli quittée), rien n'est publié…
        owner.registry.currentState = Lifecycle.State.STARTED
        view.value = TvViewDto(screen = "browse")
        assertEquals(2, published.size)
        // … et la vue courante est republiée au retour.
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf("home", "search", "browse"), published.map { it.screen })
        job.cancel()
    }

    @Test fun null_view_publishes_nothing() = runTest {
        val view = MutableStateFlow<TvViewDto?>(null)
        val published = mutableListOf<TvViewDto>()
        val job = launch(Dispatchers.Main) { publishWhileResumed(owner.lifecycle, view) { published += it } }
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(emptyList<TvViewDto>(), published)
        view.value = TvViewDto(screen = "work", workId = 1)
        assertEquals(listOf("work"), published.map { it.screen })
        job.cancel()
    }
}
