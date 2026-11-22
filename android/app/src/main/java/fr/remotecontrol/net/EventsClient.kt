package fr.remotecontrol.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class EventsClient(
    private val client: OkHttpClient,
    private val baseUrl: () -> HttpUrl?,
    private val reconnectDelayMs: Long = 2_000,
) {
    /** Cold flow of server events; reconnects every [reconnectDelayMs] until the collector is cancelled. */
    fun events(): Flow<ServerEvent> = callbackFlow {
        val loop = launch {
            while (isActive) {
                val url = baseUrl()?.resolve("api/events")
                if (url != null) {
                    val ended = CompletableDeferred<Unit>()
                    val ws = client.newWebSocket(
                        Request.Builder().url(url).build(),
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                parseServerEvent(text)?.let { trySend(it) }
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(1000, null)
                                ended.complete(Unit)
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                ended.complete(Unit)
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                ended.complete(Unit)
                            }
                        },
                    )
                    try {
                        ended.await()
                    } finally {
                        ws.close(1000, null)
                    }
                }
                delay(reconnectDelayMs)
            }
        }
        awaitClose { loop.cancel() }
    }
}
