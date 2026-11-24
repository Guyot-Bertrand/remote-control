package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.testing.FakeRcApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayLauncherTest {
    private val api = FakeRcApi()
    private val launcher = PlayLauncher(api)

    @Test fun ok_is_started() = runBlocking {
        assertEquals(PlayOutcome.Started, launcher.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start))
        assertEquals(listOf("play(${PlayRequestDto("work", 1, "start", null, null, null)})"), api.calls)
    }

    @Test fun volume_absent_is_failed() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Server("volume_absent", "DISK1"))
        assertEquals(
            PlayOutcome.Failed("Disque « DISK1 » non branché"),
            launcher.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start),
        )
    }

    @Test fun unreachable_is_failed() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Unreachable)
        assertEquals(
            PlayOutcome.Failed("Serveur injoignable"),
            launcher.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start),
        )
    }

    @Test fun player_not_responding_is_failed() = runBlocking {
        api.playResult = ApiResult.Err(ApiError.Server("player", null))
        assertEquals(
            PlayOutcome.Failed("Le lecteur ne répond pas."),
            launcher.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start),
        )
    }

    @Test fun player_missing_reported_at_once() = runBlocking {
        var reported = 0
        val l = PlayLauncher(api) { reported++ }
        api.playResult = ApiResult.Err(ApiError.Server("player_missing", null))
        assertEquals(
            PlayOutcome.Failed("Le lecteur vidéo (mpv) n'est pas installé sur le PC."),
            l.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start),
        )
        assertEquals(1, reported)
        api.playResult = ApiResult.Err(ApiError.Server("player", null))
        l.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start)
        assertEquals(1, reported)
    }

    @Test fun choice_is_forwarded() = runBlocking {
        launcher.launch(PlayTarget(TargetKind.Work, 1), StartMode.Start, TrackChoice(2, 1, SubSel.None))
        assertEquals(listOf("play(${PlayRequestDto("work", 1, "start", 2, 1, SubSel.None)})"), api.calls)
    }
}
