package fr.remotecontrol.net

import fr.remotecontrol.testing.Fixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventsClientTest {
    private lateinit var server: MockWebServer
    private val client = rcOkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun upgrade(listener: WebSocketListener) =
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())

    private fun sending(vararg texts: String, closeAfter: Boolean = false) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            texts.forEach { webSocket.send(it) }
            if (closeAfter) webSocket.close(1000, null)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun eventsClient(urls: () -> okhttp3.HttpUrl? = { server.url("/") }) =
        EventsClient(client, urls, reconnectDelayMs = 50)

    @Test
    fun receives_hello_then_tv() = runBlocking {
        upgrade(sending(Fixtures.text("ws_hello.json"), Fixtures.text("ws_tv.json")))
        val events = withTimeout(5_000) { eventsClient().events().take(2).toList() }
        assertTrue(events[0] is ServerEvent.Hello)
        assertTrue(events[1] is ServerEvent.Tv)
    }

    @Test
    fun ignores_unknown_messages() = runBlocking {
        upgrade(sending("""{"type":"bogus"}""", Fixtures.text("ws_catalog.json")))
        val first = withTimeout(5_000) { eventsClient().events().first() }
        assertEquals(ServerEvent.Catalog(6), first)
    }

    @Test
    fun reconnects_after_close() = runBlocking {
        upgrade(sending(Fixtures.text("ws_hello.json"), closeAfter = true))
        upgrade(sending(Fixtures.text("ws_catalog.json")))
        val events = withTimeout(5_000) { eventsClient().events().take(2).toList() }
        assertTrue(events[0] is ServerEvent.Hello)
        assertEquals(ServerEvent.Catalog(6), events[1])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun waits_when_no_server() = runBlocking {
        upgrade(sending(Fixtures.text("ws_hello.json")))
        val calls = AtomicInteger()
        val c = eventsClient { if (calls.incrementAndGet() <= 2) null else server.url("/") }
        val first = withTimeout(5_000) { c.events().first() }
        assertTrue(first is ServerEvent.Hello)
        assertEquals(3, calls.get())
    }

    @Test
    fun cancel_closes_socket() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        upgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Fixtures.text("ws_hello.json"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        })
        withTimeout(5_000) { eventsClient().events().take(1).toList() }
        withTimeout(1_000) { closed.await() }
    }

    @Test
    fun cancel_stops_reconnect_loop() = runBlocking {
        val calls = AtomicInteger()
        val c = eventsClient { calls.incrementAndGet(); null }
        val job = launch(Dispatchers.Default) { c.events().collect {} }
        withTimeout(5_000) { while (calls.get() < 2) delay(10) }
        job.cancelAndJoin()
        val after = calls.get()
        delay(300)
        assertEquals(after, calls.get())
    }
}
