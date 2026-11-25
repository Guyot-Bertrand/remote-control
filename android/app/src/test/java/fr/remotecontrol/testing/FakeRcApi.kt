package fr.remotecontrol.testing

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.InfoDto
import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.net.TvViewDto
import kotlinx.coroutines.CompletableDeferred

/** Scriptable [RcApi]; every call is recorded in [calls] (e.g. `"sync(null)"`, `"tvShow(work,1)"`). */
class FakeRcApi : RcApi {
    val calls = mutableListOf<String>()

    var infoResult: ApiResult<InfoDto> = ApiResult.Err(ApiError.Unreachable)
    var syncResponses: ArrayDeque<ApiResult<SyncDto>> = ArrayDeque()
    var tracksResult: ApiResult<MediaTracksDto> = ApiResult.Ok(MediaTracksDto())
    var tvShowResult: ApiResult<Boolean> = ApiResult.Ok(true)
    var playResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var pauseResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var seekResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var volumeResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var audioResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var subtitleResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var stopResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var playerStateResult: ApiResult<PlayerSnapshotDto> = ApiResult.Ok(PlayerSnapshotDto())

    override suspend fun info() = infoResult.also { calls += "info()" }

    /** When set, sync() records its call, then waits for this gate before answering. */
    var syncGate: CompletableDeferred<Unit>? = null

    override suspend fun sync(since: Long?): ApiResult<SyncDto> {
        calls += "sync($since)"
        syncGate?.await()
        return syncResponses.removeFirstOrNull() ?: ApiResult.Err(ApiError.Unreachable)
    }

    /** Réponse de tracks() par fichier ; à défaut, [tracksResult]. */
    val tracksByFile = mutableMapOf<Long, ApiResult<MediaTracksDto>>()

    override suspend fun tracks(mediaFileId: Long): ApiResult<MediaTracksDto> {
        calls += "tracks($mediaFileId)"
        return tracksByFile[mediaFileId] ?: tracksResult
    }
    override suspend fun tvShow(screen: String, id: Long?) = tvShowResult.also { calls += "tvShow($screen,$id)" }

    /** Vues reçues par tvView(), dans l'ordre. */
    val views = mutableListOf<TvViewDto>()
    var tvViewResult: ApiResult<Boolean> = ApiResult.Ok(true)

    override suspend fun tvView(view: TvViewDto): ApiResult<Boolean> {
        views += view
        calls += "tvView(${view.screen},${view.seq})"
        return tvViewResult
    }

    /** When set, play() records its call, then waits for this gate before answering. */
    var playGate: CompletableDeferred<Unit>? = null

    override suspend fun play(request: PlayRequestDto): ApiResult<Unit> {
        calls += "play($request)"
        playGate?.await()
        return playResult
    }
    override suspend fun pause(paused: Boolean) = pauseResult.also { calls += "pause($paused)" }
    override suspend fun seek(seconds: Double, relative: Boolean) =
        seekResult.also { calls += "seek($seconds,$relative)" }
    override suspend fun volume(level: Int) = volumeResult.also { calls += "volume($level)" }
    override suspend fun audio(index: Int?) = audioResult.also { calls += "audio($index)" }
    override suspend fun subtitle(sel: SubSel) = subtitleResult.also { calls += "subtitle($sel)" }
    override suspend fun stop() = stopResult.also { calls += "stop()" }
    override suspend fun playerState() = playerStateResult.also { calls += "playerState()" }
}
