package fr.remotecontrol.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.sync.SyncOutcome
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.qr.QrTarget
import fr.remotecontrol.qr.parseQr
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.Texts
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [busy] : un code reconnu est en cours de traitement ou a déjà ouvert sa fiche ; l'analyse est alors coupée. */
data class ScanUi(val message: String? = null, val busy: Boolean = false)

/**
 * Traitement des textes décodés par la caméra (appelé à chaque image où un QR-code est lu) :
 * - code reconnu → une seule navigation vers la fiche, puis plus rien jusqu'à [rearm] (retour sur l'écran) ;
 * - fiche absente du cache local → synchronisation, puis la fiche quand même (elle affiche « introuvable ») ;
 *   si la synchronisation échoue, son message s'affiche à la place (une fois pour ce code, retenté après « OK ») ;
 * - autre contenu → [Texts.qrUnknown], signalé une fois tant que le même texte revient.
 */
class ScanViewModel(private val dao: CatalogDao, private val sync: SyncRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScanUi())
    val state: StateFlow<ScanUi> = _state.asStateFlow()

    // Sans tampon : une navigation n'est livrée que si l'écran l'écoute, jamais rejouée au retour.
    private val nav = Channel<NavEvent>(Channel.RENDEZVOUS)
    val events: Flow<NavEvent> = nav.receiveAsFlow()

    private var lastUnknown: String? = null

    /** Code dont la synchronisation a échoué : pas de nouvel essai à chaque image tant que le message est affiché. */
    private var lastFailed: String? = null
    private var pending: Job? = null

    fun onDecoded(text: String) {
        if (_state.value.busy || text == lastFailed) return
        val target = parseQr(text)
        if (target == null) {
            if (text != lastUnknown) {
                lastUnknown = text
                _state.update { it.copy(message = Texts.qrUnknown) }
            }
            return
        }
        _state.update { it.copy(message = null, busy = true) }
        pending = viewModelScope.launch {
            if (!target.isCached()) {
                val outcome = sync.sync()
                if (outcome is SyncOutcome.Failed && !target.isCached()) {
                    lastFailed = text
                    _state.update { it.copy(message = outcome.error.messageFr(), busy = false) }
                    return@launch
                }
            }
            // Écran absent (aucun collecteur) : on relâche pour que l'image suivante réessaie.
            if (nav.trySend(target.toEvent()).isFailure) _state.update { it.copy(busy = false) }
        }
    }

    fun clearMessage() {
        lastFailed = null
        _state.update { it.copy(message = null) }
    }

    /** Retour sur l'écran de scan : l'analyse reprend, et un code déjà signalé peut l'être à nouveau. */
    fun rearm() {
        if (pending?.isActive == true) return // un code est encore en traitement : sa navigation suit
        lastUnknown = null
        lastFailed = null
        _state.update { it.copy(busy = false) }
    }

    private suspend fun QrTarget.isCached() = when (this) {
        is QrTarget.Work -> dao.hasWork(id)
        is QrTarget.Season -> dao.hasSeason(id)
    }

    private fun QrTarget.toEvent(): NavEvent = when (this) {
        is QrTarget.Work -> NavEvent.ToWork(id)
        is QrTarget.Season -> NavEvent.ToSeason(id)
    }
}
