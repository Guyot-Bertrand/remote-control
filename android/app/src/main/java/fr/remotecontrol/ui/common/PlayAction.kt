package fr.remotecontrol.ui.common

import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayOutcome
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TrackChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Lancement d'une lecture depuis un écran, autour de [PlayLauncher] :
 * - un seul lancement à la fois ([launching]) : un double appui n'envoie qu'une requête ;
 * - [PlayOutcome.Started] → [NavEvent.ToRemote] sur [events], livré seulement si l'écran l'écoute à cet instant
 *   (canal sans tampon, `trySend`) : jamais rejoué quand on revient sur la fiche ;
 * - [PlayOutcome.Failed] → [message], effacé au lancement suivant.
 */
class PlayAction(private val scope: CoroutineScope, private val launcher: PlayLauncher) {
    private val nav = Channel<NavEvent>(Channel.RENDEZVOUS)
    val events: Flow<NavEvent> = nav.receiveAsFlow()

    private val _launching = MutableStateFlow(false)
    val launching: StateFlow<Boolean> = _launching.asStateFlow()

    val message = MutableStateFlow<String?>(null)

    fun launch(target: PlayTarget, start: StartMode, choice: TrackChoice? = null) {
        if (_launching.value) return
        _launching.value = true
        message.value = null
        scope.launch {
            try {
                when (val outcome = launcher.launch(target, start, choice)) {
                    PlayOutcome.Started -> nav.trySend(NavEvent.ToRemote)
                    is PlayOutcome.Failed -> message.value = outcome.message
                }
            } finally {
                _launching.value = false
            }
        }
    }
}
