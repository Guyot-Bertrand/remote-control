package fr.remotecontrol.player

import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.TrackDto
import fr.remotecontrol.ui.common.Texts

/** Piste audio proposée : [index] 1-based, comme le serveur. */
data class TrackOption(val index: Int, val label: String)

/** Nom de la langue, suivi de « — titre » si la piste a un titre différent de ce nom. */
fun trackOptions(tracks: List<TrackDto>): List<TrackOption> = tracks.map { t ->
    val language = Texts.languageName(t.lang)
    val label = t.title?.takeIf { it.isNotBlank() && it != language }?.let { "$language — $it" } ?: language
    TrackOption(t.index, label)
}

/** [label] : libellé court (télécommande) ; [name] et [detail] : les deux lignes de l'écran de choix. */
data class SubtitleOption(val sel: SubSel, val label: String, val name: String = label, val detail: String? = null)

/** « Aucun », puis les fichiers externes (chemin absolu côté serveur), puis les pistes intégrées. */
fun subtitleOptions(t: MediaTracksDto): List<SubtitleOption> =
    listOf(SubtitleOption(SubSel.None, Texts.subtitleNone)) +
        t.external.map {
            SubtitleOption(SubSel.External(it.path), Texts.externalSub(it.lang), Texts.languageName(it.lang), Texts.subFile(it.path))
        } +
        t.subtitles.map {
            SubtitleOption(SubSel.Embedded(it.index), Texts.embeddedSub(it.lang), Texts.languageName(it.lang), Texts.subEmbedded)
        }
