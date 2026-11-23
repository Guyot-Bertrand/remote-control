package fr.remotecontrol.ui.settings

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.discovery.ServerAddress
import fr.remotecontrol.discovery.ServerDiscovery
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.ServerProbe
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.FakeKeyValueStore
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private var reachable = true
    private val probe = ServerProbe { if (reachable) InfoDto("rc", 1, 1) else null }
    private lateinit var locator: ServerLocator
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() {
        db = testDb()
        locator = ServerLocator(FakeKeyValueStore(), probe, ServerDiscovery { emptyFlow() })
        vm = SettingsViewModel(locator, SyncRepository(db, api), db.catalogDao())
    }

    // Room exécute ses requêtes suspendues hors du dispatcher de test.
    private suspend fun awaitMessage() = withTimeout(5_000) { vm.state.first { it.message != null } }

    @After fun tearDown() = db.close()

    @Test fun server_label_detected() = runBlocking {
        val store = FakeKeyValueStore(mutableMapOf(
            "server.host" to "192.168.1.20", "server.port" to "8765", "server.manual" to "false"))
        val l = ServerLocator(store, probe, ServerDiscovery { emptyFlow() })
        l.locate()
        val v = SettingsViewModel(l, SyncRepository(db, api), db.catalogDao())
        assertEquals("192.168.1.20:8765 (détecté)", v.state.value.serverLabel)
    }

    @Test fun invalid_address_message() {
        vm.onManualInput("a b")
        vm.saveManual()
        assertEquals("Adresse invalide", vm.state.value.message)
    }

    @Test fun unreachable_manual_message() {
        reachable = false
        vm.onManualInput("10.0.0.9")
        vm.saveManual()
        assertEquals("Serveur injoignable à cette adresse", vm.state.value.message)
    }

    @Test fun manual_ok_triggers_full_sync() = runBlocking {
        val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
        api.syncResponses.addLast(ApiResult.Ok(full))
        vm.onManualInput("10.0.0.9")
        vm.saveManual()
        awaitMessage()
        assertTrue("sync(null)" in api.calls)
        assertEquals("Catalogue synchronisé (${full.works.size} œuvres)", vm.state.value.message)
        assertEquals(full.works.size, vm.state.value.workCount)
    }

    @Test fun resync_failure_message() = runBlocking {
        api.syncResponses.addLast(ApiResult.Err(ApiError.Unreachable))
        vm.resync()
        awaitMessage()
        assertEquals("Serveur injoignable", vm.state.value.message)
    }
}
