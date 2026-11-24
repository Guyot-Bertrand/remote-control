package fr.remotecontrol.player

import fr.remotecontrol.data.db.EpisodeRow
import fr.remotecontrol.data.db.MediaFileRow
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.ui.common.Texts

/** Qualités lisibles, de la meilleure à la moins bonne. */
val QUALITY_ORDER = listOf("UHD", "2HD", "FHD", "HD", "SD", "BD")

sealed interface Availability {
    data object Present : Availability
    data class VolumeAbsent(val label: String?) : Availability
    data object NotDigitized : Availability
}

/** Un fichier présent : lisible ; aucun fichier : non numérisé ; sinon le disque (premier libellé connu) manque. */
fun availabilityOf(files: List<MediaFileRow>): Availability = when {
    files.any { it.present } -> Availability.Present
    files.isEmpty() -> Availability.NotDigitized
    else -> Availability.VolumeAbsent(files.firstNotNullOfOrNull { it.volumeLabel })
}

fun Availability.label(): String? = when (this) {
    Availability.Present -> null
    is Availability.VolumeAbsent -> Texts.volumeAbsent(label)
    Availability.NotDigitized -> Texts.notDigitized
}

private fun qualityRank(quality: String): Int =
    QUALITY_ORDER.indexOf(quality).let { if (it < 0) QUALITY_ORDER.size else it }

/** Qualités triées de la meilleure à la moins bonne ([QUALITY_ORDER]), inconnues en dernier. */
fun sortQualities(qualities: Collection<String>): List<String> = qualities.distinct().sortedBy(::qualityRank)

/** Tri par qualité ([QUALITY_ORDER]), qualités inconnues en dernier ; tri stable sinon. */
fun sortVersions(files: List<MediaFileRow>): List<MediaFileRow> = files.sortedBy { qualityRank(it.quality) }

/**
 * Version à proposer : en reprise, celle de la progression si elle est présente ;
 * sinon la meilleure version présente ; `null` si aucune n'est présente.
 */
fun preferredVersion(files: List<MediaFileRow>, progressMediaFileId: Long?, start: StartMode): Long? {
    if (start == StartMode.Resume && progressMediaFileId != null) {
        files.firstOrNull { it.id == progressMediaFileId && it.present }?.let { return it.id }
    }
    return sortVersions(files).firstOrNull { it.present }?.id
}

/**
 * Règle du serveur (`highlight_episode_id`) : l'épisode dont la progression est la plus récente (vu ou entamé) ;
 * sinon le premier épisode non vu ; `null` si la saison est vide.
 */
fun highlightEpisode(rows: List<EpisodeRow>): Long? {
    rows.filter { it.updatedAt != null }.maxByOrNull { it.updatedAt ?: 0.0 }?.let { return it.id }
    return (rows.firstOrNull { it.watched != true } ?: rows.firstOrNull())?.id
}

/**
 * Résumé du choix qui sera lu (lien « Modifier » de la fiche) : qualité de la version proposée (celle de la
 * reprise si l'œuvre est entamée), puis le choix de sous-titres mémorisé s'il y en a un ; nul sans version présente.
 */
fun choiceSummary(files: List<MediaFileRow>, progress: ProgressEntity?): String? {
    val resume = progress != null && progress.position > 0 && !progress.watched
    val id = preferredVersion(files, progress?.mediaFileId, if (resume) StartMode.Resume else StartMode.Start) ?: return null
    val subtitles = when (progress?.subtitle) {
        null -> null
        SubSel.None -> Texts.withoutSubtitles
        else -> Texts.withSubtitles
    }
    return Texts.details(files.first { it.id == id }.quality, subtitles)
}
