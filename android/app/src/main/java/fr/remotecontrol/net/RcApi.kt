package fr.remotecontrol.net

import fr.remotecontrol.ui.common.Texts
import okhttp3.HttpUrl

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
    data object NoServer : ApiError
    data object Unreachable : ApiError
    /** Réponse 409 : [message] est la phrase du serveur, affichée seulement pour un [code] inconnu de l'appli. */
    data class Server(val code: String, val volumeLabel: String?, val message: String? = null) : ApiError

    /**
     * Autre statut d'erreur ; [code] et [message] : ceux du corps (`{"error", "message"}`) s'il en porte un.
     * Comme pour [Server], [message] n'est affiché que pour un [code] inconnu de l'appli.
     */
    data class Http(val status: Int, val code: String? = null, val message: String? = null) : ApiError
    data object BadResponse : ApiError
}

fun ApiError.messageFr(): String = when (this) {
    ApiError.NoServer -> Texts.noServer
    ApiError.Unreachable -> Texts.serverUnreachable
    is ApiError.Server -> when (code) {
        "volume_absent" -> Texts.volumeAbsent(volumeLabel)
        "not_playable" -> Texts.notPlayable
        "not_found" -> Texts.notFound
        "idle" -> Texts.idle
        "player" -> Texts.playerNotResponding
        "player_missing" -> Texts.playerMissing
        else -> message ?: Texts.httpError(409)
    }
    is ApiError.Http -> when (code) {
        "internal" -> Texts.serverInternal
        "not_found" -> Texts.itemNotFound
        "player_missing" -> Texts.playerMissing
        else -> message ?: Texts.httpError(status)
    }
    ApiError.BadResponse -> Texts.badResponse
}

interface RcApi {
    suspend fun info(): ApiResult<InfoDto>
    suspend fun sync(since: Long?): ApiResult<SyncDto>
    suspend fun tracks(mediaFileId: Long): ApiResult<MediaTracksDto>
    suspend fun tvShow(screen: String, id: Long?): ApiResult<Boolean>
    suspend fun tvView(view: TvViewDto): ApiResult<Boolean>
    suspend fun play(request: PlayRequestDto): ApiResult<Unit>
    suspend fun pause(paused: Boolean): ApiResult<Unit>
    suspend fun seek(seconds: Double, relative: Boolean): ApiResult<Unit>
    suspend fun volume(level: Int): ApiResult<Unit>
    suspend fun audio(index: Int?): ApiResult<Unit>
    suspend fun subtitle(sel: SubSel): ApiResult<Unit>
    suspend fun stop(): ApiResult<Unit>
    suspend fun playerState(): ApiResult<PlayerSnapshotDto>
}

fun interface ServerProbe {
    /** `null` si injoignable, JSON invalide ou `api != 1`. */
    suspend fun probe(base: HttpUrl): InfoDto?
}
