package fr.remotecontrol.discovery

import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.data.META_SERVER_HOST
import fr.remotecontrol.data.META_SERVER_MANUAL
import fr.remotecontrol.data.META_SERVER_PORT
import fr.remotecontrol.net.ServerProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

sealed interface ServerState {
    data object Searching : ServerState
    data class Connected(val address: ServerAddress, val manual: Boolean) : ServerState
    data object NotFound : ServerState
}

class ServerLocator(
    private val store: KeyValueStore,
    private val probe: ServerProbe,
    private val discovery: ServerDiscovery,
    private val discoveryTimeoutMs: Long = 5_000,
) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Searching)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun currentBaseUrl(): HttpUrl? = (state.value as? ServerState.Connected)?.address?.baseUrl

    suspend fun locate() {
        _state.value = ServerState.Searching
        val host = store.get(META_SERVER_HOST)
        val port = store.get(META_SERVER_PORT)?.toIntOrNull()
        val storedManual = store.get(META_SERVER_MANUAL) == "true"
        if (host != null && port != null) {
            val stored = ServerAddress(host, port)
            if (probe.probe(stored.baseUrl) != null) {
                _state.value = ServerState.Connected(stored, storedManual)
                return
            }
        }
        val found = withTimeoutOrNull(discoveryTimeoutMs) {
            discovery.discover().firstOrNull { probe.probe(it.baseUrl) != null }
        }
        if (found != null) {
            remember(found, manual = false)
            _state.value = ServerState.Connected(found, manual = false)
        } else {
            _state.value = ServerState.NotFound
        }
    }

    suspend fun setManual(address: ServerAddress): Boolean {
        if (probe.probe(address.baseUrl) == null) return false
        remember(address, manual = true)
        _state.value = ServerState.Connected(address, manual = true)
        return true
    }

    private suspend fun remember(address: ServerAddress, manual: Boolean) {
        store.put(META_SERVER_HOST, address.host)
        store.put(META_SERVER_PORT, address.port.toString())
        store.put(META_SERVER_MANUAL, manual.toString())
    }
}
