package fr.remotecontrol.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.buffer
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
    private val _connected = MutableStateFlow(false)
    private val _failures = MutableStateFlow(0)

    /** Vrai tant que le WebSocket est ouvert. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Tentatives consécutives en échec (connexion refusée, coupure, fermeture avant ouverture) ; remis à 0 à l'ouverture. */
    val failures: StateFlow<Int> = _failures.asStateFlow()

    /** Cold flow of server events; reconnects every [reconnectDelayMs] until the collector is cancelled. */
    fun events(): Flow<ServerEvent> = callbackFlow {
        val loop = launch {
            while (isActive) {
                val url = baseUrl()?.resolve("api/events")
                if (url != null) {
                    val ended = CompletableDeferred<Unit>()
                    val opened = AtomicBoolean(false)
                    val failed = AtomicBoolean(false)
                    val ws = client.newWebSocket(
                        Request.Builder().url(url).build(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                opened.set(true)
                                _failures.value = 0
                                _connected.value = true
                            }

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
                                failed.set(true)
                                ended.complete(Unit)
                            }
                        },
                    )
                    try {
                        ended.await()
                        // Échec : connexion refusée ou coupée, ou fermeture avant ouverture. Une fermeture
                        // propre d'un socket ouvert n'en est pas un.
                        if (failed.get() || !opened.get()) _failures.update { it + 1 }
                    } finally {
                        _connected.value = false
                        ws.close(1000, null)
                    }
                }
                delay(reconnectDelayMs)
            }
        }
        awaitClose { loop.cancel() }
    }.buffer(Channel.UNLIMITED) // events are low-rate state snapshots: never drop the newest for a slow collector
}
