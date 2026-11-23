package fr.remotecontrol.ui.work

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.NavEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val vms = mutableListOf<WorkViewModel>()

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun viewModel(id: Long) =
        WorkViewModel(id, db.catalogDao(), api, PlayLauncher(api)).also { vms += it }

    // Room répond hors du dispatcher de test : on attend l'état voulu.
    private suspend fun WorkViewModel.await(predicate: (WorkUi) -> Boolean = { true }) =
        withTimeout(5_000) { state.filterNotNull().first(predicate) }

    @Test fun init_sends_tv_show_once() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.await()
        assertEquals(1, api.calls.count { it == "tvShow(work,1)" })
    }

    @Test fun tv_show_failure_is_silent() = runBlocking {
        api.tvShowResult = ApiResult.Err(ApiError.Unreachable)
        val ui = viewModel(1).await()
        assertNull(ui.message)
        assertEquals("Ava", ui.work.titleFr)
        assertFalse(ui.onTv)
    }

    @Test fun tv_show_not_shown_during_playback_is_not_an_error() = runBlocking {
        api.tvShowResult = ApiResult.Ok(false)
        val ui = viewModel(1).await()
        assertNull(ui.message)
        assertFalse(ui.onTv)
    }

    @Test fun tv_shown_is_flagged() = runBlocking {
        assertTrue(viewModel(1).await { it.onTv }.onTv)
    }

    @Test fun ava_state() = runBlocking {
        val ui = viewModel(1).await()
        assertEquals("Films", ui.typeLabel)
        assertEquals("1 h 39", ui.durationLabel)
        assertEquals("Reprendre à 00:10", ui.resumeLabel)
        assertTrue(ui.canPlay)
        assertNull(ui.availabilityLabel)
        assertEquals(listOf("FHD · 3.8G", "HD · 1.6G"), ui.versions.map { it.label })
        assertEquals(listOf("Les 1000 plus grands films de tous les temps [2020]"), ui.collections.map { it.name })
    }

    @Test fun theatre_absent_disk() = runBlocking {
        val ui = viewModel(4).await()
        assertEquals("Disque « Théâtre » non branché", ui.availabilityLabel)
        assertFalse(ui.canPlay)
        assertEquals(listOf(false), ui.versions.map { it.present })
        assertEquals("Disque « Théâtre » non branché", ui.versions.single().absentLabel)
    }

    @Test fun alien_not_digitized() = runBlocking {
        val ui = viewModel(2).await()
        assertEquals("Non numérisé", ui.availabilityLabel)
        assertFalse(ui.canPlay)
        assertNull(ui.resumeLabel)
    }

    @Test fun play_success_navigates_to_remote() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.playFromStart()
        assertEquals(NavEvent.ToRemote, withTimeout(5_000) { vm.events.first() })
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "start")})"))
    }

    @Test fun resume_sends_resume_without_tracks() = runBlocking {
        val vm = viewModel(1)
        vm.await()
        vm.resume()
        assertEquals(NavEvent.ToRemote, withTimeout(5_000) { vm.events.first() })
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "resume")})"))
    }

    @Test fun play_error_shows_message() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Server("volume_absent", "DISK1"))
        val vm = viewModel(1)
        vm.await()
        vm.playFromStart()
        assertEquals("Disque « DISK1 » non branché", vm.await { it.message != null }.message)
        assertNull(withTimeoutOrNull(200) { vm.events.first() })
        vm.clearMessage()
        assertNull(vm.await { it.message == null }.message)
    }

    @Test fun series_lists_seasons_no_play() = runBlocking {
        val ui = viewModel(5).await { it.seasons.isNotEmpty() }
        assertEquals(listOf(1L, 2L), ui.seasons.map { it.id })
        assertFalse(ui.canPlay)
        assertNull(ui.availabilityLabel)
    }
}
