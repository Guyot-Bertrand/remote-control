package fr.remotecontrol.player

import fr.remotecontrol.net.PlayRequestDto
import fr.remotecontrol.net.SubSel

enum class TargetKind(val wire: String) { Work("work"), Episode("episode") }

data class PlayTarget(val kind: TargetKind, val id: Long)

enum class StartMode(val wire: String) { Start("start"), Resume("resume") }

/** Version et pistes choisies sur la feuille de choix (Task 11). */
data class TrackChoice(val mediaFileId: Long, val audio: Int?, val subtitle: SubSel)

/**
 * Sans [choice], `media_file_id`, `audio` et `subtitle` restent nuls : le serveur applique ses choix par
 * défaut (version mémorisée, dernières pistes).
 */
fun playRequest(target: PlayTarget, start: StartMode, choice: TrackChoice? = null): PlayRequestDto =
    PlayRequestDto(
        targetKind = target.kind.wire,
        targetId = target.id,
        start = start.wire,
        mediaFileId = choice?.mediaFileId,
        audio = choice?.audio,
        subtitle = choice?.subtitle,
    )
