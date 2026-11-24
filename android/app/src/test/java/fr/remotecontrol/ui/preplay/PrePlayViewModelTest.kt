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
import fr.remotecontrol.net.TrackDefaultsDto
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
import fr.remotecontrol.ui.common.PrePlayRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun tracksWith(audio: Int?, subtitle: SubSel) = ApiResult.Ok(tracks.copy(default = TrackDefaultsDto(audio, subtitle)))

    @Test fun switch_version_uses_new_defaults_and_sends_them() = runBlocking {
        api.tracksByFile[2] = tracksWith(1, external)
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.await { it.canConfirm }
        vm.selectVersion(2)
        val ui = vm.await { it.selectedMediaFileId == 2L && it.canConfirm }
        assertEquals(1, ui.selectedAudio)
        assertEquals(external, ui.selectedSubtitle)
        vm.confirm()
        assertTrue(api.calls.contains("play(${PlayRequestDto("work", 1, "start", 2, 1, external)})"))
    }

    @Test fun failure_then_switch_clears_message() = runBlocking {
        api.tracksByFile[1] = ApiResult.Err(ApiError.Server("volume_absent", "Théâtre"))
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        assertTrue(vm.loaded().canRetry)
        vm.selectVersion(2)
        val ui = vm.await { it.selectedMediaFileId == 2L && !it.loading }
        assertNull(ui.message)
        assertFalse(ui.canRetry)
        assertTrue(ui.canConfirm)
    }

    @Test fun retry_reloads_failed_tracks() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Unreachable)
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        assertEquals("Serveur injoignable", vm.loaded().message)
        api.tracksResult = ApiResult.Ok(tracks)
        vm.retry()
        val ui = vm.await { it.canConfirm }
        assertNull(ui.message)
        assertEquals(2, api.calls.count { it == "tracks(1)" })
    }

    @Test fun selecting_same_version_reloads_when_tracks_missing() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Unreachable)
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.loaded()
        api.tracksResult = ApiResult.Ok(tracks)
        vm.selectVersion(1)
        vm.await { it.canConfirm }
        assertEquals(2, api.calls.count { it == "tracks(1)" })
    }

    @Test fun selecting_same_loaded_version_does_not_reload() = runBlocking {
        val vm = viewModel(TargetKind.Work, 1, StartMode.Start)
        vm.await { it.canConfirm }
        vm.selectVersion(1)
        assertEquals(1, api.calls.count { it == "tracks(1)" })
    }

    @Test fun tracks_not_found() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Server("not_found", null))
        val ui = viewModel(TargetKind.Work, 1, StartMode.Start).loaded()
        assertEquals("Fichier introuvable", ui.message)
        assertFalse(ui.canConfirm)
        assertTrue(ui.canRetry)
    }

    @Test fun tracks_unreachable() = runBlocking {
        api.tracksResult = ApiResult.Err(ApiError.Unreachable)
        val ui = viewModel(TargetKind.Work, 1, StartMode.Start).loaded()
        assertEquals("Serveur injoignable", ui.message)
        assertFalse(ui.canConfirm)
    }

    @Test fun work_header_and_default_badges() = runBlocking {
        // Progression de l'œuvre 1 sans pistes mémorisées : seulement « Par défaut ».
        val ui = viewModel(TargetKind.Work, 1, StartMode.Start).await { it.canConfirm && it.title.isNotEmpty() }
        assertEquals("Ava", ui.title)
        assertEquals("Ces choix seront repris la prochaine fois pour ce film.", ui.footer)
        assertEquals(mapOf(1L to "Par défaut"), ui.versionBadges)
        assertEquals(mapOf(2 to "Par défaut"), ui.audioBadges)
        assertEquals(mapOf<SubSel, String>(SubSel.None to "Par défaut"), ui.subtitleBadges)
    }

    @Test fun episode_header_and_last_choice_badge() = runBlocking {
        // Progression de l'épisode 2 : version 6, audio 1 mémorisés.
        api.tracksResult = tracksWith(1, SubSel.None)
        val ui = viewModel(TargetKind.Episode, 2, StartMode.Resume).await { it.canConfirm && it.title.isNotEmpty() }
        assertEquals("Back to Life", ui.title)
        assertEquals("S01E02", ui.details)
        assertEquals("Ces choix seront repris la prochaine fois pour cet épisode.", ui.footer)
        assertEquals(mapOf(6L to "Dernier choix"), ui.versionBadges)
        assertEquals(mapOf(1 to "Dernier choix"), ui.audioBadges)
        assertEquals(mapOf<SubSel, String>(SubSel.None to "Par défaut"), ui.subtitleBadges)
    }

    @Test fun route_round_trip() {
        val args = PrePlayArgs(PlayTarget(TargetKind.Episode, 2), StartMode.Resume)
        assertEquals(PrePlayRoute("episode", 2, "resume"), args.toRoute())
        assertEquals(args, args.toRoute().toArgs())
    }
}
