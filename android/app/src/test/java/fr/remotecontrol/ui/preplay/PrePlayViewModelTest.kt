package fr.remotecontrol.ui.preplay

import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.sync.SyncWriter
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.MainDispatcherRule
import fr.remotecontrol.testing.clearAndJoin
import fr.remotecontrol.testing.collectNow
import fr.remotecontrol.testing.testDb
import fr.remotecontrol.ui.common.NavEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrePlayViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val tracks: MediaTracksDto = RcJson.decodeFromString(Fixtures.text("tracks.json"))
    private val external = SubSel.External("/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt")
    private val vms = mutableListOf<PrePlayViewModel>()

    @Before fun setUp() = runBlocking {
        db = testDb()
        SyncWriter(db).apply(full)
        api.tracksResult = ApiResult.Ok(tracks)
    }

    @After fun tearDown() = runBlocking {
        vms.forEach { it.clearAndJoin() }
        db.close()
    }

    private fun viewModel(kind: TargetKind, id: Long, start: StartMode) =
        PrePlayViewModel(PrePlayArgs(PlayTarget(kind, id), start), db.catalogDao(), api, PlayLauncher(api)).also { vms += it }

    // Room répond hors du dispatcher de test : on attend l'état voulu.
    private suspend fun PrePlayViewModel.await(predicate: (PrePlayUi) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun PrePlayViewModel.loaded() = await { !it.loading }

    @Test fun defaults_from_server() = runBlocking {
        val ui = viewModel(TargetKind.Work, 1, StartMode.Start).loaded()
        assertEquals(1L, ui.selectedMediaFileId)
        assertEquals(2, ui.selectedAudio)
        assertEquals(SubSel.None, ui.selectedSubtitle)
        assertTrue(ui.canConfirm)
        assertTrue(api.calls.contains("tracks(1)"))
    }

    @Test fun resume_preselects_progress_version() = runBlocking {
        val ui = viewModel(TargetKind.Work, 1, StartMode.Resume).loaded()
        assertEquals(2L, ui.selectedMediaFileId)
        assertTrue(api.calls.contains("tracks(2)"))
    }

    @Test fun confirm_untouched_sends_displayed_defaults() = runBlocking {
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.await { it.canConfirm }
        vm.confirm()
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "start", 1, 2, SubSel.None)})"))
    }

    @Test fun confirm_sends_choice() = runBlocking {
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.await { it.canConfirm }
        val nav = collectNow(vm.events)
        vm.selectAudio(1)
        vm.selectSubtitle(external)
        vm.confirm()
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "start", 1, 1, external)})"))
        assertEquals(listOf(NavEvent.ToRemote), nav.items)
        nav.stop()
    }

    @Test fun select_version_reloads_tracks() = runBlocking {
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.await { it.canConfirm }
        vm.selectVersion(2)
        val ui = vm.await { it.selectedMediaFileId == 2L && !it.loading }
        assertTrue(api.calls.contains("tracks(2)"))
        assertTrue(ui.canConfirm)
    }

    @Test fun tracks_volume_absent() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Server("volume_absent", "Théâtre"))
        val ui = viewModel(TargetKind.Work, 1, StartMode.Start).loaded()
        assertEquals("Disque « Théâtre » non branché", ui.message)
        assertFalse(ui.canConfirm)
    }

    @Test fun absent_version_is_listed_but_not_selectable() = runBlocking {
        val vm = viewModel(TargetKind.Work, 4, StartMode.Start)
        val ui = vm.loaded()
        assertEquals(listOf(4L), ui.versions.map { it.mediaFileId })
        assertEquals(null, ui.selectedMediaFileId)
        assertFalse(ui.canConfirm)
        vm.selectVersion(4)
        assertEquals(null, vm.state.value.selectedMediaFileId)
        assertFalse(api.calls.any { it.startsWith("tracks(") })
    }

    @Test fun episode_target_uses_episode_files() = runBlocking {
        val ui = viewModel(TargetKind.Episode, 2, StartMode.Start).loaded()
        assertEquals(listOf(6L), ui.versions.map { it.mediaFileId })
    }
}
