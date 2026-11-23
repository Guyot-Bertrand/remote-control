package fr.remotecontrol.data.db

data class WorkSummary(
    val id: Long,
    val type: String,
    val titleFr: String,
    val year: Int?,
    val poster: String?,
    val playable: Boolean,
    val episodic: Boolean,
)

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
