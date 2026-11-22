package fr.remotecontrol.net

import fr.remotecontrol.testing.Fixtures
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpRcApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpRcApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpRcApi(rcOkHttpClient(), { server.url("/") })
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    @Test
    fun sync_without_since_omits_param() = runTest {
        server.enqueue(ok(Fixtures.text("sync_full.json")))
        val r = api.sync(null)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/sync", req.url.encodedPath)
        assertNull(req.url.queryParameter("since"))
        assertEquals(5, (r as ApiResult.Ok).value.works.size)
    }

    @Test
    fun sync_with_since() = runTest {
        server.enqueue(ok(Fixtures.text("sync_incremental.json")))
        api.sync(3)
        assertEquals("3", server.takeRequest().url.queryParameter("since"))
    }

    @Test
    fun sync_gzip_body_decoded() = runTest {
        val buf = Buffer()
        GzipSink(buf).buffer().use { it.writeUtf8(Fixtures.text("sync_full.json")) }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(buf).build())
        assertEquals(5, (api.sync(null) as ApiResult.Ok).value.works.size)
    }

    @Test
    fun tracks_409_volume_absent() = runTest {
        server.enqueue(MockResponse.Builder().code(409).body(Fixtures.text("error_volume_absent.json")).build())
        val r = api.tracks(7)
        assertEquals("/api/media/7/tracks", server.takeRequest().url.encodedPath)
        val err = (r as ApiResult.Err).error
        assertEquals(ApiError.Server("volume_absent", "Théâtre"), err)
        assertEquals("Disque « Théâtre » non branché", err.messageFr())
    }

    @Test
    fun play_posts_contract_body() = runTest {
        server.enqueue(ok("""{"ok": true}"""))
        val r = api.play(PlayRequestDto("episode", 2, "start"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/play", req.url.encodedPath)
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("episode", body["target_kind"]!!.jsonPrimitive.content)
        assertEquals("start", body["start"]!!.jsonPrimitive.content)
        assertEquals(ApiResult.Ok(Unit), r)
    }

    @Test
    fun audio_null_sent_explicitly() = runTest {
        server.enqueue(ok("""{"ok": true}"""))
        api.audio(null)
        val req = server.takeRequest()
        assertEquals("/api/player/audio", req.url.encodedPath)
        assertEquals("""{"index":null}""", req.body!!.utf8())
    }

    @Test
    fun seek_body() = runTest {
        server.enqueue(ok("""{"ok": true}"""))
        api.seek(-10.0, relative = true)
        assertEquals("""{"seconds":-10.0,"relative":true}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun player_idle_409() = runTest {
        server.enqueue(MockResponse.Builder().code(409).body(Fixtures.text("error_idle.json")).build())
        val err = (api.pause(true) as ApiResult.Err).error
        assertEquals(ApiError.Server("idle", null), err)
        assertEquals("Aucune lecture en cours", err.messageFr())
    }

    @Test
    fun tv_show_returns_shown() = runTest {
        server.enqueue(ok("""{"shown": false}"""))
        val r = api.tvShow("work", 1)
        val req = server.takeRequest()
        assertEquals("/api/tv/show", req.url.encodedPath)
        assertEquals("""{"screen":"work","id":1}""", req.body!!.utf8())
        assertEquals(ApiResult.Ok(false), r)
    }

    @Test
    fun no_server_no_request() = runTest {
        val noServer = OkHttpRcApi(rcOkHttpClient(), { null })
        assertEquals(ApiResult.Err(ApiError.NoServer), noServer.info())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachable_maps_to_Unreachable() = runTest {
        server.close()
        assertEquals(ApiResult.Err(ApiError.Unreachable), api.info())
    }

    @Test
    fun base_url_reread_each_call() = runTest {
        val second = MockWebServer()
        second.start()
        try {
            var current: HttpUrl = server.url("/")
            val a = OkHttpRcApi(rcOkHttpClient(), { current })
            server.enqueue(ok(Fixtures.text("info.json")))
            second.enqueue(ok(Fixtures.text("info.json")))
            a.info()
            current = second.url("/")
            a.info()
            assertEquals(1, server.requestCount)
            assertEquals(1, second.requestCount)
        } finally {
            second.close()
        }
    }

    @Test
    fun probe_rejects_other_api() = runTest {
        server.enqueue(ok("""{"name":"x","api":2,"revision":0}"""))
        assertNull(api.probe(server.url("/")))
        server.enqueue(ok(Fixtures.text("info.json")))
        assertNotNull(api.probe(server.url("/")))
    }

    @Test
    fun invalid_2xx_body_maps_to_BadResponse() = runTest {
        server.enqueue(ok("<html>"))
        assertEquals(ApiResult.Err(ApiError.BadResponse), api.info())
        assertEquals("Réponse inattendue du serveur.", ApiError.BadResponse.messageFr())
    }

    @Test
    fun cancelling_coroutine_cancels_call() = runBlocking {
        val client = rcOkHttpClient()
        val slow = OkHttpRcApi(client, { server.url("/") })
        server.enqueue(MockResponse.Builder().body(Fixtures.text("info.json")).headersDelay(10, TimeUnit.SECONDS).build())
        val job = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { slow.info() }
        server.takeRequest()
        val start = System.nanoTime()
        job.cancelAndJoin()
        withTimeout(3_000) {
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("call still running after ${elapsedMs}ms", elapsedMs < 3_000)
        assertTrue(job.isCancelled)
    }
}
