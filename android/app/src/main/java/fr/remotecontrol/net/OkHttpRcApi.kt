package fr.remotecontrol.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

fun rcOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

class OkHttpRcApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> HttpUrl?,
    private val json: Json = RcJson,
    /** Le décodage (jusqu'au catalogue complet) ne se fait jamais dans le contexte de l'appelant. */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RcApi, ServerProbe {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun info() = get("api/info", InfoDto.serializer())

    override suspend fun sync(since: Long?) =
        get("api/sync", SyncDto.serializer()) { if (since != null) addQueryParameter("since", since.toString()) }

    override suspend fun tracks(mediaFileId: Long) =
        get("api/media/$mediaFileId/tracks", MediaTracksDto.serializer())

    override suspend fun tvShow(screen: String, id: Long?): ApiResult<Boolean> {
        val body = json.encodeToString(TvShowRequestDto.serializer(), TvShowRequestDto(screen, id))
        return when (val r = post("api/tv/show", body, TvShowResultDto.serializer())) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.shown)
            is ApiResult.Err -> r
        }
    }

    override suspend fun play(request: PlayRequestDto) =
        postUnit("api/play", json.encodeToString(PlayRequestDto.serializer(), request))

    override suspend fun pause(paused: Boolean) =
        postUnit("api/player/pause", obj("paused" to JsonPrimitive(paused)))

    override suspend fun seek(seconds: Double, relative: Boolean) =
        postUnit("api/player/seek", obj("seconds" to JsonPrimitive(seconds), "relative" to JsonPrimitive(relative)))

    override suspend fun volume(level: Int) = postUnit("api/player/volume", obj("level" to JsonPrimitive(level)))

    override suspend fun audio(index: Int?) =
        postUnit("api/player/audio", obj("index" to (index?.let { JsonPrimitive(it) } ?: JsonNull)))

    override suspend fun subtitle(sel: SubSel) =
        postUnit("api/player/subtitle", json.encodeToString(SubSel.serializer(), sel))

    override suspend fun stop() = postUnit("api/player/stop", "{}")

    override suspend fun playerState() = get("api/player/state", PlayerSnapshotDto.serializer())

    override suspend fun probe(base: HttpUrl): InfoDto? {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/info").build()).build()
        val r = execute(request) { json.decodeFromString(InfoDto.serializer(), it) }
        return (r as? ApiResult.Ok)?.value?.takeIf { it.api == 1 }
    }

    private fun obj(vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf(*fields)).toString()

    private suspend fun postUnit(path: String, body: String): ApiResult<Unit> =
        execute(buildRequest(path, {}) { it.post(body.toRequestBody(jsonType)) }) { }

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: HttpUrl.Builder.() -> Unit = {},
    ): ApiResult<T> = execute(buildRequest(path, query) { it }) { json.decodeFromString(serializer, it) }

    private suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): ApiResult<T> =
        execute(buildRequest(path, {}) { it.post(body.toRequestBody(jsonType)) }) {
            json.decodeFromString(serializer, it)
        }

    /** `null` si aucune base n'est disponible (le fournisseur est relu à chaque appel). */
    private fun buildRequest(
        path: String,
        query: HttpUrl.Builder.() -> Unit,
        configure: (Request.Builder) -> Request.Builder,
    ): Request? {
        val base = baseUrl() ?: return null
        val url = base.newBuilder().addPathSegments(path).apply(query).build()
        return configure(Request.Builder().url(url)).build()
    }

    /** Le corps d'une réponse 2xx n'est décodé que par `decode` (ignoré pour `postUnit`). */
    private suspend fun <T> execute(request: Request?, decode: (String) -> T): ApiResult<T> {
        if (request == null) return ApiResult.Err(ApiError.NoServer)
        val (code, text) = try {
            call(request)
        } catch (_: IOException) {
            return ApiResult.Err(ApiError.Unreachable)
        }
        return when {
            code == 409 -> {
                val err = withContext(decodeDispatcher) {
                    runCatching { json.decodeFromString(ErrorDto.serializer(), text) }.getOrNull()
                }
                if (err != null) ApiResult.Err(ApiError.Server(err.error, err.volumeLabel))
                else ApiResult.Err(ApiError.Http(409))
            }
            code !in 200..299 -> ApiResult.Err(ApiError.Http(code))
            else -> try {
                ApiResult.Ok(withContext(decodeDispatcher) { decode(text) })
            } catch (_: SerializationException) {
                ApiResult.Err(ApiError.BadResponse)
            } catch (_: IllegalArgumentException) {
                ApiResult.Err(ApiError.BadResponse)
            }
        }
    }

    /** Appel annulable : l'annulation de la coroutine annule le `Call` OkHttp. */
    private suspend fun call(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = it.body.string()
                            cont.resume(it.code to text)
                        } catch (e: Exception) {
                            // jamais de continuation laissée en suspens
                            if (cont.isActive) cont.resumeWithException(e as? IOException ?: IOException(e))
                        }
                    }
                }
            })
        }
}
