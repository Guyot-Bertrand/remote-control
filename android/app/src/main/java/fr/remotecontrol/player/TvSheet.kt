package fr.remotecontrol.player

import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.TvStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Dernier état connu de la télé, d'après les événements du serveur. [run] tourne au premier
 * plan ; à l'arrêt, l'état est oublié (`null` : inconnu) plutôt que gardé périmé.
 */
class TvStatus(private val events: Flow<ServerEvent>) {
    private val _tv = MutableStateFlow<TvStateDto?>(null)
    val tv: StateFlow<TvStateDto?> = _tv.asStateFlow()

    suspend fun run() {
        try {
            events.collect { e ->
                when (e) {
                    is ServerEvent.Hello -> _tv.value = e.tv
                    is ServerEvent.Tv -> _tv.value = e.state
                    is ServerEvent.Player, is ServerEvent.Catalog -> Unit
                }
            }
        } finally {
            _tv.value = null
        }
    }
}

/**
 * Mention « Affichée sur la télé » d'une fiche ([screen] `work` ou `season`, d'identifiant [id]) : [onTv] suit
 * en direct l'état de la télé. La fiche n'est pas envoyée ici : l'écran publie sa vue ([fr.remotecontrol.mirror.PublishTvView]).
 */
class TvSheet(
    scope: CoroutineScope,
    status: TvStatus,
    private val screen: String,
    private val id: Long,
) {
    /** La télé affiche cette fiche (faux hors ligne, pendant une lecture ou si elle montre autre chose). */
    val onTv: StateFlow<Boolean> = status.tv.map { it != null && matches(it) }
        .stateIn(scope, SharingStarted.Eagerly, status.tv.value?.let(::matches) == true)

    /** Vue du téléphone reproduite (arbitrage 15), ou fiche de `tv/show` d'un serveur de l'itération 1. */
    private fun matches(tv: TvStateDto): Boolean {
        val (shown, workId, seasonId) = when (tv.screen) {
            "view" -> tv.view?.let { Triple(it.screen, it.workId, it.seasonId) } ?: return false
            else -> Triple(tv.screen, tv.workId, tv.seasonId)
        }
        return shown == screen && (if (screen == "season") seasonId else workId) == id
    }
}
