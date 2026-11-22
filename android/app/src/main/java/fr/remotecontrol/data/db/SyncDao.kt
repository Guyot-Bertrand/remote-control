package fr.remotecontrol.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface SyncDao {
    @Upsert suspend fun upsertWorks(items: List<WorkEntity>)
    @Query("INSERT OR REPLACE INTO work_fts(rowid, text) VALUES (:id, :text)")
    suspend fun replaceFts(id: Long, text: String)

    @Transaction
    suspend fun upsertFts(items: List<WorkFtsEntity>) {
        items.forEach { replaceFts(it.rowid, it.text) }
    }

    @Query("DELETE FROM work_fts WHERE rowid IN (:ids)")
    suspend fun deleteFts(ids: List<Long>)

    @Upsert suspend fun upsertSeasons(items: List<SeasonEntity>)
    @Upsert suspend fun upsertEpisodes(items: List<EpisodeEntity>)
    @Upsert suspend fun upsertMediaFiles(items: List<MediaFileEntity>)
    @Upsert suspend fun upsertVolumes(items: List<VolumeEntity>)
    @Upsert suspend fun upsertCollections(items: List<CollectionEntity>)
    @Upsert suspend fun upsertWorkCollections(items: List<WorkCollectionEntity>)
    @Upsert suspend fun upsertProgress(items: List<ProgressEntity>)

    @Query("DELETE FROM media_file WHERE workId IN (:ids)")
    suspend fun deleteFilesOfWorks(ids: List<Long>)

    @Query(
        "DELETE FROM media_file WHERE episodeId IN " +
            "(SELECT e.id FROM episode e JOIN season s ON e.seasonId = s.id WHERE s.workId IN (:workIds))",
    )
    suspend fun deleteFilesOfEpisodesOfWorks(workIds: List<Long>)

    @Query("DELETE FROM episode WHERE seasonId IN (SELECT id FROM season WHERE workId IN (:workIds))")
    suspend fun deleteEpisodesOfWorks(workIds: List<Long>)

    @Query("DELETE FROM season WHERE workId IN (:workIds)")
    suspend fun deleteSeasonsOfWorks(workIds: List<Long>)

    @Query("DELETE FROM work_collection WHERE workId IN (:workIds)")
    suspend fun deleteWorkCollectionsOfChunk(workIds: List<Long>)

    @Query("DELETE FROM work WHERE id IN (:ids)")
    suspend fun deleteWorksRaw(ids: List<Long>)

    @Query(
        "DELETE FROM media_file WHERE episodeId IN (SELECT id FROM episode WHERE seasonId IN (:seasonIds))",
    )
    suspend fun deleteFilesOfSeasons(seasonIds: List<Long>)

    @Query("DELETE FROM episode WHERE seasonId IN (:seasonIds)")
    suspend fun deleteEpisodesOfSeasons(seasonIds: List<Long>)

    @Query("DELETE FROM season WHERE id IN (:ids)")
    suspend fun deleteSeasonsRaw(ids: List<Long>)

    @Query("DELETE FROM media_file WHERE episodeId IN (:episodeIds)")
    suspend fun deleteFilesOfEpisodes(episodeIds: List<Long>)

    @Query("DELETE FROM episode WHERE id IN (:ids)")
    suspend fun deleteEpisodesRaw(ids: List<Long>)

    @Query("DELETE FROM media_file WHERE id IN (:ids)")
    suspend fun deleteMediaFilesRaw(ids: List<Long>)

    @Query("DELETE FROM work_collection WHERE collectionId IN (:ids)")
    suspend fun deleteLinksOfCollections(ids: List<Long>)

    @Query("DELETE FROM collection WHERE id IN (:ids)")
    suspend fun deleteCollectionsRaw(ids: List<Long>)

    @Transaction
    suspend fun deleteWorksCascade(ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach {
            deleteFilesOfWorks(it)
            deleteFilesOfEpisodesOfWorks(it)
            deleteEpisodesOfWorks(it)
            deleteSeasonsOfWorks(it)
            deleteWorkCollectionsOfChunk(it)
            deleteFts(it)
            deleteWorksRaw(it)
        }
    }

    @Transaction
    suspend fun deleteSeasonsCascade(ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach {
            deleteFilesOfSeasons(it)
            deleteEpisodesOfSeasons(it)
            deleteSeasonsRaw(it)
        }
    }

    @Transaction
    suspend fun deleteEpisodesCascade(ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach {
            deleteFilesOfEpisodes(it)
            deleteEpisodesRaw(it)
        }
    }

    @Transaction
    suspend fun deleteMediaFiles(ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { deleteMediaFilesRaw(it) }
    }

    @Transaction
    suspend fun deleteWorkCollectionsOf(workIds: List<Long>) {
        workIds.chunked(SQL_CHUNK).forEach { deleteWorkCollectionsOfChunk(it) }
    }

    @Transaction
    suspend fun deleteCollections(ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach {
            deleteLinksOfCollections(it)
            deleteCollectionsRaw(it)
        }
    }

    @Query("DELETE FROM work_fts")
    suspend fun clearFts()

    @Query("DELETE FROM work_collection")
    suspend fun clearWorkCollections()

    @Query("DELETE FROM media_file")
    suspend fun clearMediaFiles()

    @Query("DELETE FROM episode")
    suspend fun clearEpisodes()

    @Query("DELETE FROM season")
    suspend fun clearSeasons()

    @Query("DELETE FROM work")
    suspend fun clearWorks()

    @Query("DELETE FROM collection")
    suspend fun clearCollectionsRaw()

    @Query("DELETE FROM volume")
    suspend fun clearVolumes()

    @Query("DELETE FROM progress")
    suspend fun clearProgress()

    /** Vide toutes les tables du catalogue, sauf `meta`. */
    @Transaction
    suspend fun clearCatalog() {
        clearFts(); clearWorkCollections(); clearMediaFiles(); clearEpisodes(); clearSeasons()
        clearWorks(); clearCollectionsRaw(); clearVolumes(); clearProgress()
    }

    @Query(
        "UPDATE work SET playable = EXISTS(SELECT 1 FROM media_file m WHERE m.present = 1 AND " +
            "(m.workId = work.id OR m.episodeId IN " +
            "(SELECT e.id FROM episode e JOIN season s ON e.seasonId = s.id WHERE s.workId = work.id)))",
    )
    suspend fun recomputePlayable()
}

/** Limite de 999 variables du SQLite d'Android 10 : listes découpées par paquets. */
const val SQL_CHUNK = 500
