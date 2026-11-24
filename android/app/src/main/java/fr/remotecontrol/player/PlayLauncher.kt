package fr.remotecontrol.player

import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.messageFr

sealed interface PlayOutcome {
    data object Started : PlayOutcome
    data class Failed(val message: String) : PlayOutcome
}

/**
 * Le seul chemin de lancement d'une lecture (fiches œuvre et saison, feuille de choix).
 * L'appelant traduit [PlayOutcome.Started] en navigation vers la télécommande, [PlayOutcome.Failed] en message.
 */
class PlayLauncher(private val api: RcApi) {
    suspend fun launch(target: PlayTarget, start: StartMode, choice: TrackChoice? = null): PlayOutcome =
        when (val result = api.play(playRequest(target, start, choice))) {
            is ApiResult.Ok -> PlayOutcome.Started
            is ApiResult.Err -> PlayOutcome.Failed(result.error.messageFr())
        }
}
