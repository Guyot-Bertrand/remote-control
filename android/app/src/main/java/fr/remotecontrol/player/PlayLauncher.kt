package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
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
class PlayLauncher(
    private val api: RcApi,
    /** `409 player_missing` : le lecteur n'est pas installé sur le PC (bandeau affiché aussitôt). */
    private val onPlayerMissing: () -> Unit = {},
) {
    suspend fun launch(target: PlayTarget, start: StartMode, choice: TrackChoice? = null): PlayOutcome =
        when (val result = api.play(playRequest(target, start, choice))) {
            is ApiResult.Ok -> PlayOutcome.Started
            is ApiResult.Err -> {
                if ((result.error as? ApiError.Server)?.code == "player_missing") onPlayerMissing()
                PlayOutcome.Failed(result.error.messageFr())
            }
        }
}
