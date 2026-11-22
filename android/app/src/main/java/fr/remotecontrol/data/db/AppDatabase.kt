package fr.remotecontrol.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WorkEntity::class, SeasonEntity::class, EpisodeEntity::class, MediaFileEntity::class,
        VolumeEntity::class, CollectionEntity::class, WorkCollectionEntity::class,
        ProgressEntity::class, MetaEntity::class, WorkFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun syncDao(): SyncDao
    abstract fun metaDao(): MetaDao
}

fun buildDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "rc-cache.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
