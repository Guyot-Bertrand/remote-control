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
}
