package fr.remotecontrol.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseBuildTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: AppDatabase? = null

    // Pas le nom réel : l'application de test (RcApp) ouvre déjà sa propre base sous ce nom.
    private val name = "downgrade-test.db"

    @After fun tearDown() {
        db?.close()
        context.deleteDatabase(name)
    }

    /** Cache d'une version de développement plus récente (schéma 4) : effacé, jamais de plantage. */
    @Test fun newer_cache_is_wiped_on_downgrade() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            it.execSQL("INSERT INTO meta VALUES ('sync.revision', '9')")
            it.version = 4
        }
        val opened = buildDatabase(context, name).also { db = it }
        assertNull(opened.metaDao().get("sync.revision"))
        assertEquals(0, opened.catalogDao().workCount())
        assertEquals(3, opened.openHelper.readableDatabase.version)
    }

    /** Cache d'une version précédente de l'appli (schéma 1) : effacé à la mise à jour, jamais de plantage. */
    @Test fun older_cache_is_wiped_on_upgrade() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            it.execSQL("INSERT INTO meta VALUES ('sync.revision', '9')")
            it.version = 1
        }
        val opened = buildDatabase(context, name).also { db = it }
        assertNull(opened.metaDao().get("sync.revision"))
        assertEquals(0, opened.catalogDao().workCount())
        assertEquals(3, opened.openHelper.readableDatabase.version)
    }

    /** Cache de l'étape 3 (schéma 2) : effacé à la mise à jour vers la version 3. */
    @Test fun v2_cache_is_wiped_on_upgrade() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            it.execSQL("INSERT INTO meta VALUES ('sync.revision', '9')")
            it.version = 2
        }
        val opened = buildDatabase(context, name).also { db = it }
        assertNull(opened.metaDao().get("sync.revision"))
        assertEquals(0, opened.catalogDao().workCount())
        assertEquals(3, opened.openHelper.readableDatabase.version)
    }
}
