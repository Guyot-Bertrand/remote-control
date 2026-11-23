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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUi(
    val serverLabel: String,
    val manualInput: String = "",
    val revision: Long? = null,
    val workCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val locator: ServerLocator,
    private val sync: SyncRepository,
    private val dao: CatalogDao,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUi(serverLabel = label(locator.state.value)))
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    private val initJob: Job

    init {
        viewModelScope.launch {
            locator.state.collect { s -> _state.update { it.copy(serverLabel = label(s)) } }
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
        _state.update { it.copy(revision = revision, workCount = count) }
        return count
    }

    fun onManualInput(text: String) = _state.update { it.copy(manualInput = text) }

    fun saveManual() {
        val address = parseManualAddress(_state.value.manualInput)
        if (address == null) {
            _state.update { it.copy(message = Texts.invalidAddress) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            if (!locator.setManual(address)) {
                _state.update { it.copy(busy = false, message = Texts.addressUnreachable) }
            } else {
                fullSync()
            }
        }
    }

    fun rediscover() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                locator.locate(force = true)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun resync() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
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
            _state.update { it.copy(message = message) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
