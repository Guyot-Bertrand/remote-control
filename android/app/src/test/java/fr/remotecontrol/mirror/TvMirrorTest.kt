package fr.remotecontrol.mirror

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.TvViewDto
import fr.remotecontrol.testing.FakeKeyValueStore
import fr.remotecontrol.testing.FakeRcApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvMirrorTest {
    private val api = FakeRcApi()
    private val store = FakeKeyValueStore()

    private fun view(title: String) = TvViewDto(screen = "browse", title = title)

    @Test
    fun fast_updates_are_coalesced_and_last_wins() = runTest {
        val mirror = TvMirror(api, store, clock = { testScheduler.currentTime })
        val job = launch { mirror.run() }
        runCurrent()
        repeat(10) { i ->
            mirror.publish(view("v${i + 1}"))
            advanceTimeBy(30)
        }
        advanceTimeBy(1_000)
        assertTrue(api.views.size <= 2)
        assertEquals("v10", api.views.last().title)
        job.cancel()
    }

    @Test
    fun nothing_sent_outside_run() = runTest {
        val mirror = TvMirror(api, store, clock = { testScheduler.currentTime })
        mirror.publish(view("a"))
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(api.views.isEmpty())
    }

    @Test
    fun resends_last_view_when_foregrounded() = runTest {
        val mirror = TvMirror(api, store, clock = { testScheduler.currentTime })
        val first = launch { mirror.run() }
        runCurrent()
        mirror.publish(view("a"))
        advanceTimeBy(100)
        first.cancel()
        runCurrent()
        assertEquals(1, api.views.size)
        advanceTimeBy(1_000)
        val second = launch { mirror.run() }
        advanceTimeBy(100)
        assertEquals(2, api.views.size)
        assertEquals("a", api.views[1].title)
        assertTrue(api.views[1].seq > api.views[0].seq)
        second.cancel()
    }

    @Test
    fun seq_is_time_based_and_strictly_increasing() = runTest {
        val mirror = TvMirror(api, store, clock = { 1_000L })
        val job = launch { mirror.run() }
        runCurrent()
        repeat(3) {
            mirror.publish(view("v$it"))
            advanceTimeBy(600)
        }
        assertEquals(listOf(1_000L, 1_001L, 1_002L), api.views.map { it.seq })
        job.cancel()
    }

    @Test
    fun phone_id_is_stable_across_instances() = runTest {
        suspend fun phoneIdOf(mirror: TvMirror): String {
            val job = launch { mirror.run() }
            runCurrent()
            mirror.publish(view("x"))
            advanceTimeBy(100)
            job.cancel()
            runCurrent()
            return api.views.last().phoneId
        }
        val a = phoneIdOf(TvMirror(api, store))
        val b = phoneIdOf(TvMirror(api, store))
        assertTrue(a.length in 1..64)
        assertEquals(a, b)
        assertEquals(a, store.map["tv.phone_id"])
    }

    @Test
    fun send_failure_is_silent() = runTest {
        api.tvViewResult = ApiResult.Err(ApiError.Unreachable)
        val mirror = TvMirror(api, store, clock = { testScheduler.currentTime })
        val job = launch { mirror.run() }
        runCurrent()
        mirror.publish(view("a"))
        advanceTimeBy(600)
        mirror.publish(view("b"))
        advanceTimeBy(600)
        assertEquals(listOf("a", "b"), api.views.map { it.title })
        assertTrue(job.isActive)
        job.cancel()
    }
}
