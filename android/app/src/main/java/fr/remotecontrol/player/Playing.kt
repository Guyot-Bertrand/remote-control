package fr.remotecontrol.player

import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.PlayerSnapshotDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Instantanés du lecteur, oubliés (nuls : inconnu) tant que le serveur n'est pas connecté : hors ligne, une
 * fiche ne propose pas de rouvrir la télécommande d'après un état périmé.
 */
fun liveWhileConnected(snapshot: Flow<PlayerSnapshotDto?>, server: Flow<ServerState>): Flow<PlayerSnapshotDto?> =
    combine(snapshot, server) { s, st -> s.takeIf { st is ServerState.Connected } }

/** Lecture en cours sur la télé (lecteur lancé, pas arrivé à la fin, en pause compris) ; nulle sinon. */
fun PlayerSnapshotDto?.live(): CurrentDto? = this?.takeIf { it.state.running && !it.state.ended }?.current

/** La lecture appartient à l'œuvre [workId] (le film lui-même, ou un épisode de la série). */
fun CurrentDto.playsWork(workId: Long) = this.workId == workId

fun CurrentDto.playsSeason(seasonId: Long) = this.seasonId == seasonId

fun CurrentDto.playsEpisode(episodeId: Long) = targetKind == TargetKind.Episode.wire && targetId == episodeId

/** Action principale d'une fiche ou d'une carte d'épisode. */
enum class SheetAction {
    /** Cette cible joue déjà sur la télé : on rouvre la télécommande, sans relancer la lecture. */
    OpenRemote,
    Play,

    /** Le lecteur n'est pas installé sur le PC : boutons de lecture désactivés, avec l'explication. */
    PlayerMissing,
}

fun sheetAction(playingHere: Boolean, playerAvailable: Boolean): SheetAction = when {
    playingHere -> SheetAction.OpenRemote
    !playerAvailable -> SheetAction.PlayerMissing
    else -> SheetAction.Play
}

/** Le serveur connecté signale l'absence du lecteur ; hors ligne, rien n'est affirmé. */
fun playerMissing(server: ServerState, playerAvailable: Boolean) = server is ServerState.Connected && !playerAvailable
