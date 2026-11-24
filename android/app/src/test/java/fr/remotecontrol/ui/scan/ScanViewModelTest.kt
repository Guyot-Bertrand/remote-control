package fr.remotecontrol.ui.scan

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.collectNow
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private lateinit var vm: ScanViewModel

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(RcJson.decodeFromString<SyncDto>(Fixtures.text("sync_full.json")))
        vm = ScanViewModel(db.catalogDao(), SyncRepository(db, api))
    }

    @After fun tearDown() = runBlocking {
        vm.clearAndJoin()
        db.close()
    }

    // Room répond hors du dispatcher de test : on attend la fin du traitement (busy retombé ou navigation faite).
    private suspend fun <T> MutableList<T>.awaitSize(n: Int) = withTimeout(5_000) {
        while (size < n) kotlinx.coroutines.delay(5)
    }

    @Test fun known_work_navigates_once() = runBlocking {
        val events = collectNow(vm.events)
        repeat(3) { vm.onDecoded("rc://w/1") }
        events.items.awaitSize(1)
        repeat(3) { vm.onDecoded("rc://w/1") }
        kotlinx.coroutines.delay(100)
        assertEquals(listOf<NavEvent>(NavEvent.ToWork(1)), events.items)
        assertTrue("l'analyse reste coupée après la navigation", vm.state.value.busy)
        events.stop()
    }

    @Test fun season_navigates() = runBlocking {
        val events = collectNow(vm.events)
        vm.onDecoded("rc://s/2")
        events.items.awaitSize(1)
        assertEquals(listOf<NavEvent>(NavEvent.ToSeason(2)), events.items)
        events.stop()
    }

    @Test fun rearm_after_return_allows_a_new_scan() = runBlocking {
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/1")
        events.items.awaitSize(1)
        vm.rearm()
        assertEquals(false, vm.state.value.busy)
        vm.onDecoded("rc://s/2")
        events.items.awaitSize(2)
        assertEquals(listOf(NavEvent.ToWork(1), NavEvent.ToSeason(2)), events.items)
        events.stop()
    }

    @Test fun no_listener_does_not_lock_scanning() = runBlocking {
        vm.onDecoded("rc://w/1")
        withTimeout(5_000) { vm.state.first { !it.busy } }
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/1")
        events.items.awaitSize(1)
        assertEquals(listOf<NavEvent>(NavEvent.ToWork(1)), events.items)
        events.stop()
    }

    @Test fun foreign_qr_message() {
        vm.onDecoded("https://x")
        assertEquals("QR-code non reconnu", vm.state.value.message)
        assertEquals(false, vm.state.value.busy)
    }

    @Test fun foreign_qr_signalled_once() {
        vm.onDecoded("https://x")
        vm.clearMessage()
        vm.onDecoded("https://x")
        assertNull(vm.state.value.message)
        vm.onDecoded("https://y")
        assertEquals("QR-code non reconnu", vm.state.value.message)
    }

    @Test fun malformed_rc_code_is_unknown() {
        vm.onDecoded("rc://w/abc")
        assertEquals("QR-code non reconnu", vm.state.value.message)
    }

    @Test fun recognised_code_clears_message() = runBlocking {
        val events = collectNow(vm.events)
        vm.onDecoded("https://x")
        vm.onDecoded("rc://w/1")
        assertNull(vm.state.value.message)
        events.items.awaitSize(1)
        events.stop()
    }

    @Test fun unknown_id_syncs_then_opens_sheet() = runBlocking {
        api.syncResponses.add(ApiResult.Ok(RcJson.decodeFromString<SyncDto>("""{"revision":4,"full":false}""")))
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/999")
        events.items.awaitSize(1)
        assertTrue(api.calls.toString(), api.calls.contains("sync(3)"))
        // La fiche affiche elle-même « Œuvre introuvable. ».
        assertEquals(listOf<NavEvent>(NavEvent.ToWork(999)), events.items)
        assertNull(vm.state.value.message)
        events.stop()
    }

    @Test fun sync_failure_for_unknown_id_is_shown_once_then_retried_after_ok() = runBlocking {
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/999") // synchronisation en échec (serveur injoignable)
        withTimeout(5_000) { vm.state.first { it.message != null && !it.busy } }
        assertEquals("Serveur injoignable", vm.state.value.message)
        vm.onDecoded("rc://w/999") // image suivante : même code, pas de nouvel essai
        kotlinx.coroutines.delay(100)
        assertEquals(1, api.calls.count { it.startsWith("sync") })
        assertTrue(events.items.isEmpty())
        vm.clearMessage() // « OK » : le code est retenté
        api.syncResponses.add(ApiResult.Ok(RcJson.decodeFromString<SyncDto>("""{"revision":4,"full":false}""")))
        vm.onDecoded("rc://w/999")
        events.items.awaitSize(1)
        assertEquals(2, api.calls.count { it.startsWith("sync") })
        assertEquals(listOf<NavEvent>(NavEvent.ToWork(999)), events.items)
        events.stop()
    }

    @Test fun unknown_id_found_after_sync() = runBlocking {
        api.syncResponses.add(ApiResult.Ok(RcJson.decodeFromString<SyncDto>(
            """{"revision":4,"full":false,"works":[{"id":999,"type":"film","key":"Neuf","title_fr":"Neuf"}]}""")))
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/999")
        events.items.awaitSize(1)
        assertTrue("synchronisé avant la navigation", db.catalogDao().hasWork(999))
        assertEquals(listOf<NavEvent>(NavEvent.ToWork(999)), events.items)
        events.stop()
    }

    @Test fun known_id_does_not_sync() = runBlocking {
        val events = collectNow(vm.events)
        vm.onDecoded("rc://w/1")
        events.items.awaitSize(1)
        assertTrue(api.calls.none { it.startsWith("sync") })
        events.stop()
    }
}
