package fr.remotecontrol.discovery

import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.data.META_SERVER_HOST
import fr.remotecontrol.data.META_SERVER_MANUAL
import fr.remotecontrol.data.META_SERVER_PORT
import fr.remotecontrol.net.ServerProbe
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

sealed interface ServerState {
    data object Searching : ServerState
    data class Connected(val address: ServerAddress, val manual: Boolean) : ServerState
    data object NotFound : ServerState
}

/**
 * Trouve le serveur et suit son joignabilité tant que l'appli est au premier plan ([monitor]) :
 * [ServerState.Connected] signifie que la dernière sonde (`GET /api/info`) a répondu.
 */
class ServerLocator(
    private val store: KeyValueStore,
    private val probe: ServerProbe,
    private val discovery: ServerDiscovery,
    private val discoveryTimeoutMs: Long = 5_000,
    private val probeTimeoutMs: Long = 3_000,
    private val retryDelayMs: Long = 20_000,
    private val wsFailureThreshold: Int = 3,
) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Searching)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /** Une seule recherche à la fois (premier plan, échecs du WebSocket, réglages). */
    private val mutex = Mutex()

    fun currentBaseUrl(): HttpUrl? = (state.value as? ServerState.Connected)?.address?.baseUrl

    /** Une adresse inutilisable (hôte refusé par OkHttp, erreur de sonde, pas de réponse à temps) compte comme muette. */
    private suspend fun alive(address: ServerAddress): Boolean = try {
        withTimeoutOrNull(probeTimeoutMs) { probe.probe(address.baseUrl) } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Suivi au premier plan, jusqu'à l'annulation : vérifie d'abord le serveur ([refresh]) ; puis, tant
     * qu'aucun serveur n'est trouvé, recherche toutes les [retryDelayMs] ; et après chaque série de
     * [wsFailureThreshold] échecs consécutifs du WebSocket ([wsFailures]), revérifie le serveur.
     */
    suspend fun monitor(wsFailures: Flow<Int>): Unit = coroutineScope {
        refresh()
        launch {
            wsFailures.filter { it > 0 && it % wsFailureThreshold == 0 }.collect { refresh() }
        }
        while (true) {
            _state.first { it == ServerState.NotFound }
            delay(retryDelayMs)
            if (_state.value == ServerState.NotFound) locate(force = true)
        }
    }

    /**
     * Retour au premier plan, ou WebSocket en échec : une sonde du serveur connecté ; s'il ne répond plus
     * (ou si aucun n'est connecté), nouvelle recherche.
     */
    suspend fun refresh() = mutex.withLock {
        val connected = _state.value as? ServerState.Connected
        if (connected != null && alive(connected.address)) return@withLock
        search(skip = connected?.address)
    }

    /** Sans [force], ne fait rien si un serveur est déjà connecté. */
    suspend fun locate(force: Boolean = false) = mutex.withLock {
        if (!force && _state.value is ServerState.Connected) return@withLock
        search(skip = null)
    }

    /** Adresse mémorisée d'abord (sauf [skip], déjà sondée sans réponse), puis découverte. */
    private suspend fun search(skip: ServerAddress?) {
        _state.value = ServerState.Searching
        try {
            val host = store.get(META_SERVER_HOST)
            val port = store.get(META_SERVER_PORT)?.toIntOrNull()
            val storedManual = store.get(META_SERVER_MANUAL) == "true"
            if (host != null && port != null) {
                val stored = ServerAddress(host, port)
                if (stored != skip && alive(stored)) {
                    _state.value = ServerState.Connected(stored, storedManual)
                    return
                }
            }
            val found = try {
                withTimeoutOrNull(discoveryTimeoutMs) {
                    discovery.discover().firstOrNull { alive(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // découverte en échec : NotFound, pas de plantage
            }
            if (found != null) {
                remember(found, manual = false)
                _state.value = ServerState.Connected(found, manual = false)
            } else {
                _state.value = ServerState.NotFound
            }
        } finally {
            if (_state.value == ServerState.Searching) _state.value = ServerState.NotFound
        }
    }

    suspend fun setManual(address: ServerAddress): Boolean = mutex.withLock {
        if (!alive(address)) return@withLock false
        remember(address, manual = true)
        _state.value = ServerState.Connected(address, manual = true)
        true
    }

    private suspend fun remember(address: ServerAddress, manual: Boolean) {
        store.put(META_SERVER_HOST, address.host)
        store.put(META_SERVER_PORT, address.port.toString())
        store.put(META_SERVER_MANUAL, manual.toString())
    }
}
