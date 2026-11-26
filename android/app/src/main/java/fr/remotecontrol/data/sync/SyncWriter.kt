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
    /**
     * Chaque liste est écrite par paquets de [ROW_BATCH], dans la même transaction. [onProgress] reçoit le nombre
     * d'œuvres écrites, leur total (`total_works`, sinon le nombre d'œuvres de la réponse) et la fraction de toutes
     * les lignes écrites (œuvres, saisons, épisodes, fichiers, collections, liens, progressions) ; le dernier appel
     * vaut `1f`.
     */
    suspend fun apply(dto: SyncDto, onProgress: (done: Int, total: Int, fraction: Float) -> Unit = { _, _, _ -> }) {
        val dao = db.syncDao()
        db.withTransaction {
            if (dto.full) dao.clearCatalog()

            val d = dto.deleted
            dao.deleteWorksCascade(d.work)
            dao.deleteSeasonsCascade(d.season)
            dao.deleteEpisodesCascade(d.episode)
            dao.deleteMediaFiles(d.mediaFile)
            dao.deleteCollections(d.collection)

            val total = dto.totalWorks ?: dto.works.size
            val totalRows = dto.works.size + dto.seasons.size + dto.episodes.size + dto.mediaFiles.size +
                dto.collections.size + dto.workCollections.size + dto.progress.size
            var written = 0
            var worksDone = 0
            fun report(count: Int) {
                written += count
                onProgress(worksDone, total, if (totalRows == 0) 1f else written.toFloat() / totalRows)
            }

            dto.works.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertWorks(batch.map { it.toEntity() })
                dao.upsertFts(batch.map { WorkFtsEntity(it.id, ftsText(it)) })
                worksDone += batch.size
                report(batch.size)
            }
            dto.seasons.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertSeasons(batch.map { SeasonEntity(it.id, it.workId, it.number, it.label) })
                report(batch.size)
            }
            dto.episodes.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertEpisodes(batch.map { EpisodeEntity(it.id, it.seasonId, it.number, it.title, it.durationMin) })
                report(batch.size)
            }
            dto.mediaFiles.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertMediaFiles(
                    batch.map {
                        MediaFileEntity(it.id, it.workId, it.episodeId, it.quality, it.size, it.relpath, it.volumeUuid, it.present, it.audioLangs, it.subtitleLangs, it.width, it.height, it.durationS)
                    },
                )
                report(batch.size)
            }
            dao.upsertVolumes(dto.volumes.map { VolumeEntity(it.uuid, it.label, it.present) })
            dto.collections.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertCollections(batch.map { CollectionEntity(it.id, it.name, it.description, it.link) })
                report(batch.size)
            }
            dto.progress.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertProgress(
                    batch.map {
                        ProgressEntity(it.targetKind, it.targetId, it.mediaFileId, it.position, it.duration, it.watched, it.updatedAt, it.audio, it.subtitle)
                    },
                )
                report(batch.size)
            }
            afterUpserts()

            // Contract: each returned work carries its full membership list; replace that work's links.
            val workIds = dto.works.map { it.id }
            dao.deleteWorkCollectionsOf(workIds)
            val returned = workIds.toSet()
            val links = dto.workCollections.filter { it.workId in returned }
            links.chunked(ROW_BATCH).forEach { batch ->
                dao.upsertWorkCollections(batch.map { WorkCollectionEntity(it.workId, it.collectionId, it.rank) })
                report(batch.size)
            }

            dao.recomputePlayable()
            db.metaDao().put(MetaEntity(META_REVISION, dto.revision.toString()))
            onProgress(worksDone, total, 1f)
        }
    }
}

private const val ROW_BATCH = 500
