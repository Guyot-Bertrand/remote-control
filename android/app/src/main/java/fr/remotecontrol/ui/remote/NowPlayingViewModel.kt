package fr.remotecontrol.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.ui.common.MiniPlayerUi
import fr.remotecontrol.ui.common.miniPlayerOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Bandeau « Sur la télé » : lecture en cours (nulle sans lecture) et message d'échec d'une commande. */
data class NowPlayingUi(val player: MiniPlayerUi? = null, val message: String? = null)

/** État et commande lecture/pause du bandeau « Sur la télé », au-dessus de la barre de navigation. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModel(
    private val api: RcApi,
    controller: RemoteController,
    playingInfo: (workId: Long, mediaFileId: Long) -> Flow<PlayingInfo?>,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    private val info: Flow<PlayingInfo?> = controller.snapshot
        .map { s -> s?.current?.let { it.workId to it.mediaFileId } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> ids?.let { (w, m) -> playingInfo(w, m) } ?: flowOf(null) }

    val state: StateFlow<NowPlayingUi> = combine(controller.snapshot, info, message) { s, i, m ->
        NowPlayingUi(miniPlayerOf(s, i), m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUi())

    fun togglePause() {
        val player = state.value.player ?: return
        viewModelScope.launch {
            val r = api.pause(!player.paused)
            message.value = (r as? ApiResult.Err)?.error?.messageFr()
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
