package fr.remotecontrol.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

    override suspend fun probe(base: HttpUrl): InfoDto? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(base.newBuilder().addPathSegments("api/info").build()).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val info = json.decodeFromString(InfoDto.serializer(), resp.body.string())
                info.takeIf { it.api == 1 }
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun obj(vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf(*fields)).toString()

    private suspend fun postUnit(path: String, body: String): ApiResult<Unit> =
        when (val r = post(path, body, Unit.serializer())) {
            is ApiResult.Ok -> r
            is ApiResult.Err -> r
        }

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: HttpUrl.Builder.() -> Unit = {},
    ): ApiResult<T> = execute(serializer, path, query) { it }

    private suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): ApiResult<T> =
        execute(serializer, path, {}) { it.post(body.toRequestBody(jsonType)) }

    private suspend fun <T> execute(
        serializer: KSerializer<T>,
        path: String,
        query: HttpUrl.Builder.() -> Unit,
        configure: (Request.Builder) -> Request.Builder,
    ): ApiResult<T> {
        val base = baseUrl() ?: return ApiResult.Err(ApiError.NoServer)
        val url = base.newBuilder().addPathSegments(path).apply(query).build()
        val request = configure(Request.Builder().url(url)).build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body.string()
                    when {
                        resp.code == 409 -> {
                            val err = runCatching { json.decodeFromString(ErrorDto.serializer(), text) }.getOrNull()
                            if (err != null) ApiResult.Err(ApiError.Server(err.error, err.volumeLabel))
                            else ApiResult.Err(ApiError.Http(409))
                        }
                        !resp.isSuccessful -> ApiResult.Err(ApiError.Http(resp.code))
                        serializer === Unit.serializer() -> {
                            @Suppress("UNCHECKED_CAST")
                            ApiResult.Ok(Unit as T)
                        }
                        else -> ApiResult.Ok(json.decodeFromString(serializer, text))
                    }
                }
            } catch (_: IOException) {
                ApiResult.Err(ApiError.Unreachable)
            }
        }
    }
}
