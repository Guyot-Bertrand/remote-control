package fr.remotecontrol.data.sync

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.net.TvStateDto
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private lateinit var repo: SyncRepository
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val events = MutableSharedFlow<ServerEvent>()

    @Before fun setUp() {
        db = testDb()
        repo = SyncRepository(db, api)
    }

    @After fun tearDown() = db.close()

    private suspend fun seedLocal(revision: Long) {
        api.syncResponses.addLast(ApiResult.Ok(full.copy(revision = revision)))
        repo.sync()
        api.calls.clear()
    }

    private fun hello(r: Long) = ServerEvent.Hello(r, TvStateDto("home"), PlayerSnapshotDto())

    /** Starts the coordinator, forgets its start-up sync, then delivers [e] (with [response] queued for it). */
    private suspend fun kotlinx.coroutines.test.TestScope.emit(e: ServerEvent, response: SyncDto? = null) {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(repo, events).run() }
        events.subscriptionCount.let { while (it.value == 0) kotlinx.coroutines.yield() }
        events.emit(ServerEvent.Tv(TvStateDto("home"))) // barrier: the start-up sync is over
        api.calls.clear()
        response?.let { api.syncResponses.addLast(ApiResult.Ok(it)) }
        events.emit(e)
        events.emit(ServerEvent.Tv(TvStateDto("home"))) // barrier: accepted only once e has been fully handled
        job.cancel()
    }

    @Test fun hello_newer_triggers_incremental() = runTest {
        seedLocal(3)
        emit(hello(5), full.copy(revision = 5, full = false))
        assertEquals(listOf("sync(3)"), api.calls)
    }

    @Test fun same_revision_no_sync() = runTest {
        seedLocal(3)
        emit(hello(3))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun lower_revision_hello_forces_full() = runTest {
        seedLocal(5)
        emit(hello(2), full.copy(revision = 2))
        assertEquals(listOf("sync(null)"), api.calls)
    }

    @Test fun stale_catalog_ignored() = runTest {
        seedLocal(5)
        emit(ServerEvent.Catalog(2))
        emit(ServerEvent.Catalog(5))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun survives_failing_writer() = runTest {
        val bad = SyncRepository(db, api, SyncWriter(db, afterUpserts = { error("panne") }))
        api.syncResponses.addLast(ApiResult.Ok(full))
        api.syncResponses.addLast(ApiResult.Ok(full))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(bad, events).run() }
        while (events.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        events.emit(hello(3))
        events.emit(hello(4))
        events.emit(ServerEvent.Tv(TvStateDto("home")))
        job.cancel()
        assertEquals(listOf("sync(null)", "sync(null)"), api.calls)
    }

    @Test fun empty_cache_forces_full() = runTest {
        emit(hello(3), full)
        assertEquals(listOf("sync(null)"), api.calls)
    }

    @Test fun startup_incremental_when_local_exists() = runTest {
        seedLocal(3)
        api.syncResponses.addLast(ApiResult.Ok(full.copy(revision = 4, full = false)))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(repo, events).run() }
        while (events.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        events.emit(ServerEvent.Tv(TvStateDto("home"))) // barrier: the start-up sync is over
        job.cancel()
        assertEquals(listOf("sync(3)"), api.calls)
        assertEquals(4L, repo.localRevision())
    }

    @Test fun startup_no_sync_without_local() = runTest {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(repo, events).run() }
        while (events.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        events.emit(ServerEvent.Tv(TvStateDto("home"))) // barrier: the start-up sync is over
        job.cancel()
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun startup_sync_then_hello_at_new_revision_syncs_once() = runTest {
        seedLocal(3)
        api.syncResponses.addLast(ApiResult.Ok(full.copy(revision = 4, full = false)))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(repo, events).run() }
        while (events.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        events.emit(hello(4))
        events.emit(ServerEvent.Tv(TvStateDto("home")))
        job.cancel()
        assertEquals(listOf("sync(3)"), api.calls)
    }

    @Test fun event_during_startup_sync_is_not_lost() = runTest {
        seedLocal(3)
        val gate = CompletableDeferred<Unit>()
        api.syncGate = gate
        api.syncResponses.addLast(ApiResult.Ok(full.copy(revision = 4, full = false)))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SyncCoordinator(repo, events).run() }
        while ("sync(3)" !in api.calls) kotlinx.coroutines.yield() // start-up sync en cours, bloquée
        val sender = launch(UnconfinedTestDispatcher(testScheduler)) {
            events.emit(hello(5))
            events.emit(ServerEvent.Tv(TvStateDto("home")))
        }
        api.syncGate = null
        api.syncResponses.addLast(ApiResult.Ok(full.copy(revision = 5, full = false)))
        gate.complete(Unit)
        sender.join()
        job.cancel()
        assertEquals(listOf("sync(3)", "sync(4)"), api.calls)
    }
}
