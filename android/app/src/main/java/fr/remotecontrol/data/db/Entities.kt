package fr.remotecontrol.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.remotecontrol.net.SubSel

@Entity(
    tableName = "work",
    indices = [Index("type"), Index("sortKey"), Index("playable")],
)
data class WorkEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val titleFr: String,
    val titleVo: String?,
    val titleAlt: String?,
    val year: Int?,
    val durationMin: Int?,
    val episodeCount: String?,
    val directors: List<String>,
    val genres: List<String>,
    val cast: List<String>,
    val synopsis: String?,
    val tags: List<String>,
    val episodic: Boolean,
    val poster: String?,
    val sortKey: String,
    val playable: Boolean = false,
    val medium: String? = null,
)

@Entity(tableName = "season", indices = [Index("workId")])
data class SeasonEntity(
    @PrimaryKey val id: Long,
    val workId: Long,
    val number: Int,
    val label: String,
)

@Entity(tableName = "episode", indices = [Index("seasonId")])
data class EpisodeEntity(
    @PrimaryKey val id: Long,
    val seasonId: Long,
    val number: Int,
    val title: String?,
    val durationMin: Int? = null,
)

@Entity(tableName = "media_file", indices = [Index("workId"), Index("episodeId")])
data class MediaFileEntity(
    @PrimaryKey val id: Long,
    val workId: Long?,
    val episodeId: Long?,
    val quality: String,
    val size: String?,
    val relpath: String,
    val volumeUuid: String?,
    val present: Boolean,
    val audioLangs: List<String>? = null,
    val subtitleLangs: List<String>? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationS: Double? = null,
)

@Entity(tableName = "volume")
data class VolumeEntity(
    @PrimaryKey val uuid: String,
    val label: String?,
    val present: Boolean,
)

@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val description: String?,
    val link: String?,
)

@Entity(
    tableName = "work_collection",
    primaryKeys = ["workId", "collectionId"],
    indices = [Index("collectionId")],
)
data class WorkCollectionEntity(
    val workId: Long,
    val collectionId: Long,
    val rank: String?,
)

@Entity(tableName = "progress", primaryKeys = ["targetKind", "targetId"])
data class ProgressEntity(
    val targetKind: String,
    val targetId: Long,
    val mediaFileId: Long?,
    val position: Double,
    val duration: Double?,
    val watched: Boolean,
    val updatedAt: Double,
    /** Dernières pistes choisies pour la cible (badge « Dernier choix » de l'écran de choix). */
    val audio: Int? = null,
    val subtitle: SubSel? = null,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Fts4
@Entity(tableName = "work_fts")
data class WorkFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val text: String,
)
