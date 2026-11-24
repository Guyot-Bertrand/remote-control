package fr.remotecontrol.data.sync

import androidx.room.withTransaction
import fr.remotecontrol.data.META_REVISION
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.CollectionEntity
import fr.remotecontrol.data.db.EpisodeEntity
import fr.remotecontrol.data.db.MediaFileEntity
import fr.remotecontrol.data.db.MetaEntity
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.VolumeEntity
import fr.remotecontrol.data.db.WorkCollectionEntity
import fr.remotecontrol.data.db.WorkFtsEntity
import fr.remotecontrol.data.ftsText
import fr.remotecontrol.data.toEntity
import fr.remotecontrol.net.SyncDto

class SyncWriter(
    private val db: AppDatabase,
    private val afterUpserts: suspend () -> Unit = {},
) {
    suspend fun apply(dto: SyncDto) {
        val dao = db.syncDao()
        db.withTransaction {
            if (dto.full) dao.clearCatalog()

            val d = dto.deleted
            dao.deleteWorksCascade(d.work)
            dao.deleteSeasonsCascade(d.season)
            dao.deleteEpisodesCascade(d.episode)
            dao.deleteMediaFiles(d.mediaFile)
            dao.deleteCollections(d.collection)

            dao.upsertWorks(dto.works.map { it.toEntity() })
            dao.upsertFts(dto.works.map { WorkFtsEntity(it.id, ftsText(it)) })
            dao.upsertSeasons(dto.seasons.map { SeasonEntity(it.id, it.workId, it.number, it.label) })
            dao.upsertEpisodes(dto.episodes.map { EpisodeEntity(it.id, it.seasonId, it.number, it.title) })
            dao.upsertMediaFiles(
                dto.mediaFiles.map {
                    MediaFileEntity(it.id, it.workId, it.episodeId, it.quality, it.size, it.relpath, it.volumeUuid, it.present)
                },
            )
            dao.upsertVolumes(dto.volumes.map { VolumeEntity(it.uuid, it.label, it.present) })
            dao.upsertCollections(dto.collections.map { CollectionEntity(it.id, it.name, it.description, it.link) })
            dao.upsertProgress(
                dto.progress.map {
                    ProgressEntity(it.targetKind, it.targetId, it.mediaFileId, it.position, it.duration, it.watched, it.updatedAt, it.audio, it.subtitle)
                },
            )
            afterUpserts()

            // Contract: each returned work carries its full membership list; replace that work's links.
            val workIds = dto.works.map { it.id }
            dao.deleteWorkCollectionsOf(workIds)
            val returned = workIds.toSet()
            dao.upsertWorkCollections(
                dto.workCollections.filter { it.workId in returned }
                    .map { WorkCollectionEntity(it.workId, it.collectionId, it.rank) },
            )

            dao.recomputePlayable()
            db.metaDao().put(MetaEntity(META_REVISION, dto.revision.toString()))
        }
    }
}
