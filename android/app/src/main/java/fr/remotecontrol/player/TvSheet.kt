package fr.remotecontrol.player

import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Dernier état connu de la télé et du lecteur, d'après les événements du serveur. [run] tourne au premier
 * plan ; à l'arrêt, l'état est oublié (`null` : inconnu) plutôt que gardé périmé.
 */
class TvStatus(private val events: Flow<ServerEvent>) {
    private val _tv = MutableStateFlow<TvStateDto?>(null)
    private val _playing = MutableStateFlow<Boolean?>(null)
    val tv: StateFlow<TvStateDto?> = _tv.asStateFlow()

    /** Une lecture est en cours (lecteur lancé, pas arrivé à la fin). */
    val playing: StateFlow<Boolean?> = _playing.asStateFlow()

    suspend fun run() {
        try {
            events.collect { e ->
                when (e) {
                    is ServerEvent.Hello -> {
                        _tv.value = e.tv
                        _playing.value = e.player.isPlaying()
                    }
                    is ServerEvent.Tv -> _tv.value = e.state
                    is ServerEvent.Player -> _playing.value = e.snapshot.isPlaying()
                    is ServerEvent.Catalog -> Unit
                }
            }
        } finally {
            _tv.value = null
            _playing.value = null
        }
    }
}

private fun PlayerSnapshotDto.isPlaying() = state.running && !state.ended

/**
 * Fiche ([screen] `work` ou `season`, d'identifiant [id]) affichée aussi sur la télé : envoyée une fois à
 * l'ouverture, puis [onTv] suit en direct l'état de la télé. Au retour sur l'écran ([onScreenResumed]), si la
 * télé montre autre chose que cette fiche et que rien n'est en lecture, la fiche est renvoyée une fois.
 */
class TvSheet(
    private val scope: CoroutineScope,
    private val api: RcApi,
    private val status: TvStatus,
    private val screen: String,
    private val id: Long,
) {
    private val _onTv = MutableStateFlow(false)

    /** La télé affiche cette fiche (faux hors ligne, pendant une lecture ou si elle montre autre chose). */
    val onTv: StateFlow<Boolean> = _onTv.asStateFlow()
    private var opened = false

    init {
        scope.launch { show() }
        // Seuls les changements comptent : l'état connu à l'ouverture précède l'envoi de la fiche.
        scope.launch { status.tv.drop(1).filterNotNull().collect { _onTv.value = matches(it) } }
    }

    fun onScreenResumed() {
        // Le premier passage est l'ouverture elle-même, déjà envoyée.
        if (!opened) {
            opened = true
            return
        }
        val tv = status.tv.value ?: return
        val playing = status.playing.value ?: return
        if (!matches(tv) && !playing) scope.launch { show() }
    }

    /**
     * Échec volontairement silencieux : afficher la fiche sur la télé n'est qu'un accompagnement de la
     * navigation sur le téléphone (hors ligne, ou télé occupée par une lecture, ce n'est pas une erreur) ;
     * seule la mention « Affichée sur la télé » en dépend. Les erreurs qui comptent (lecture) ont leur message.
     */
    private suspend fun show() {
        val shown = api.tvShow(screen, id)
        _onTv.value = shown is ApiResult.Ok && shown.value
    }

    private fun matches(tv: TvStateDto) = tv.screen == screen && when (screen) {
        "work" -> tv.workId == id
        "season" -> tv.seasonId == id
        else -> false
    }
}
