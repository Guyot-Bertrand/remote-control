package fr.remotecontrol.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.CollectionEntity
import fr.remotecontrol.data.db.EpisodeEntity
import fr.remotecontrol.data.db.MediaFileEntity
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.VolumeEntity
import fr.remotecontrol.data.db.WorkCollectionEntity
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.data.db.WorkFtsEntity

fun testDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

suspend fun AppDatabase.seed(
    works: List<WorkEntity>,
    files: List<MediaFileEntity> = emptyList(),
    fts: List<WorkFtsEntity> = emptyList(),
    seasons: List<SeasonEntity> = emptyList(),
    episodes: List<EpisodeEntity> = emptyList(),
    volumes: List<VolumeEntity> = emptyList(),
    collections: List<CollectionEntity> = emptyList(),
    workCollections: List<WorkCollectionEntity> = emptyList(),
    progress: List<ProgressEntity> = emptyList(),
) {
    syncDao().apply {
        upsertWorks(works)
        upsertFts(fts)
        upsertSeasons(seasons)
        upsertEpisodes(episodes)
        upsertMediaFiles(files)
        upsertVolumes(volumes)
        upsertCollections(collections)
        upsertWorkCollections(workCollections)
        upsertProgress(progress)
    }
}
