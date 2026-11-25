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
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun syncDao(): SyncDao
    abstract fun metaDao(): MetaDao
}

const val DATABASE_NAME = "rc-cache.db"

/**
 * La base n'est qu'un cache du catalogue (l'adresse du serveur est dans les préférences) : tout changement de
 * schéma, montée comme descente de version, l'efface, et la synchronisation suivante la remplit à nouveau.
 *
 * Ne pas ajouter `fallbackToDestructiveMigrationOnDowngrade` : appelé après, il rend les migrations de montée de
 * version obligatoires (Room 2.8) et la mise à jour de l'appli plante au démarrage. `fallbackToDestructiveMigration`
 * couvre déjà la descente.
 */
fun buildDatabase(context: Context, name: String = DATABASE_NAME): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
