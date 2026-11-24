package fr.remotecontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.sync.SyncOutcome
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.discovery.parseManualAddress
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [syncedAt] : heure de la dernière synchronisation réussie (nulle avant) ; [syncFailed] : [message] vient d'une
 * synchronisation en échec (l'écran propose alors de réessayer).
 */
data class SettingsUi(
    val serverLabel: String,
    val server: ServerState = ServerState.Searching,
    val manualInput: String = "",
    val revision: Long? = null,
    val workCount: Int = 0,
    val playableCount: Int = 0,
    val syncedAt: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val syncFailed: Boolean = false,
)

private const val CLOCK_TICK_MS = 60_000L

class SettingsViewModel(
    private val locator: ServerLocator,
    private val sync: SyncRepository,
    private val dao: CatalogDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUi(serverLabel = label(locator.state.value), server = locator.state.value))
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    /**
     * Heure courante, rafraîchie chaque minute tant que l'écran l'affiche (« Aujourd'hui / Hier » suit le
     * changement de jour) ; sans abonné depuis 5 s, l'horloge s'arrête.
     */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(clock())
            delay(CLOCK_TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock())

    private val initJob: Job

    init {
        viewModelScope.launch {
            locator.state.collect { s -> _state.update { it.copy(serverLabel = label(s), server = s) } }
        }
        viewModelScope.launch {
            sync.syncedAt.collect { at -> _state.update { it.copy(syncedAt = at) } }
        }
        initJob = viewModelScope.launch { refreshInfo() }
    }

    private fun label(s: ServerState) = when (s) {
        ServerState.Searching -> Texts.searching
        ServerState.NotFound -> Texts.noServer
        is ServerState.Connected ->
            if (s.manual) Texts.serverManual(s.address.display) else Texts.serverDetected(s.address.display)
    }

    private suspend fun refreshInfo(): Int {
        val revision = sync.localRevision()
        val count = dao.workCount()
        val playable = dao.playableCount()
        _state.update { it.copy(revision = revision, workCount = count, playableCount = playable) }
        return count
    }

    fun onManualInput(text: String) = _state.update { it.copy(manualInput = text) }

    fun clearMessage() = _state.update { it.copy(message = null, syncFailed = false) }

    fun saveManual() {
        val address = parseManualAddress(_state.value.manualInput)
        if (address == null) {
            _state.update { it.copy(message = Texts.invalidAddress, syncFailed = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, syncFailed = false) }
            if (!locator.setManual(address)) {
                _state.update { it.copy(busy = false, message = Texts.addressUnreachable, syncFailed = false) }
            } else {
                fullSync()
            }
        }
    }

    fun rediscover() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, syncFailed = false) }
            try {
                locator.locate(force = true)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun resync() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, syncFailed = false) }
            fullSync()
        }
    }

    private suspend fun fullSync() {
        initJob.join()
        try {
            val outcome = sync.sync(forceFull = true)
            val count = refreshInfo()
            val message = when (outcome) {
                is SyncOutcome.Done -> Texts.synced(count)
                is SyncOutcome.Failed -> outcome.error.messageFr()
            }
            _state.update { it.copy(message = message, syncFailed = outcome is SyncOutcome.Failed) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
