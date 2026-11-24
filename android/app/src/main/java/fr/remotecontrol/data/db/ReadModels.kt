package fr.remotecontrol.data.db

import androidx.room.Embedded

data class WorkSummary(
    val id: Long,
    val type: String,
    val titleFr: String,
    val year: Int?,
    val poster: String?,
    val playable: Boolean,
    val episodic: Boolean,
    val directors: List<String>,
    /** Position / durée quand l'œuvre est entamée et non vue ; nulle sinon. */
    val progress: Double?,
    val watched: Boolean,
    /**
     * Œuvre non lisible : libellé du disque débranché où sont ses fichiers ('' s'il n'a pas de nom) ; nul si
     * l'œuvre est lisible ou n'a aucun fichier (non numérisée).
     */
    val absentVolume: String? = null,
)

/** Résultat de recherche : l'œuvre, plus les champs qui expliquent la correspondance. */
data class SearchHit(
    @Embedded val work: WorkSummary,
    val titleVo: String?,
    val titleAlt: String?,
    val cast: List<String>,
)

/** En-tête de l'écran de choix pour un épisode : série, saison et numéro. */
data class EpisodeHeader(
    val number: Int,
    val title: String?,
    val seasonNumber: Int,
    val workTitle: String,
    val poster: String?,
    val year: Int?,
)

/** Collection d'une œuvre, avec le rang de l'œuvre dans cette collection (nul si la collection n'est pas classée). */
data class WorkCollectionRow(val id: Long, val name: String, val rank: String?)

/** Rang (texte libre, souvent numérique) d'une œuvre dans une collection. */
data class WorkRank(val workId: Long, val rank: String)

/** [count] : toutes les œuvres de la collection ; [playableCount] : celles qui sont lisibles. */
data class CollectionSummary(val id: Long, val name: String, val count: Int, val playableCount: Int)

data class ResumeRow(
    val targetKind: String,
    val targetId: Long,
    val workId: Long,
    val seasonId: Long?,
    val titleFr: String,
    val poster: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val position: Double,
    val duration: Double?,
    val updatedAt: Double,
)

data class MediaFileRow(
    val id: Long,
    val workId: Long?,
    val episodeId: Long?,
    val quality: String,
    val size: String?,
    val present: Boolean,
    val volumeLabel: String?,
)

data class EpisodeRow(
    val id: Long,
    val number: Int,
    val title: String?,
    val position: Double?,
    val duration: Double?,
    val watched: Boolean?,
    val updatedAt: Double?,
    val playable: Boolean,
)
