package fr.remotecontrol.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.remotecontrol.testing.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyValueStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefsStore() = SharedPreferencesKeyValueStore(context.getSharedPreferences("test", Context.MODE_PRIVATE))

    @Test fun preferences_store_round_trip_and_delete() = runTest {
        val store = prefsStore()
        assertNull(store.get(META_SERVER_HOST))
        store.put(META_SERVER_HOST, "10.0.0.1")
        assertEquals("10.0.0.1", prefsStore().get(META_SERVER_HOST))
        store.put(META_SERVER_HOST, null)
        assertNull(prefsStore().get(META_SERVER_HOST))
    }

    @Test fun server_address_moves_from_cache_to_preferences() = runTest {
        val meta = FakeKeyValueStore(
            mutableMapOf(META_SERVER_HOST to "10.0.0.1", META_SERVER_PORT to "8765", META_SERVER_MANUAL to "true", META_REVISION to "7"),
        )
        val prefs = FakeKeyValueStore()
        migrateServerAddress(from = meta, to = prefs)
        assertEquals("10.0.0.1", prefs.map[META_SERVER_HOST])
        assertEquals("8765", prefs.map[META_SERVER_PORT])
        assertEquals("true", prefs.map[META_SERVER_MANUAL])
        assertNull(meta.get(META_SERVER_HOST))
        assertNull(meta.get(META_SERVER_PORT))
        assertNull(meta.get(META_SERVER_MANUAL))
        assertEquals("7", meta.get(META_REVISION)) // la révision du cache reste dans Room
    }

    @Test fun migration_never_overwrites_preferences() = runTest {
        val meta = FakeKeyValueStore(mutableMapOf(META_SERVER_HOST to "10.0.0.1", META_SERVER_PORT to "8765"))
        val prefs = FakeKeyValueStore(mutableMapOf(META_SERVER_HOST to "10.0.0.2", META_SERVER_PORT to "9000"))
        migrateServerAddress(from = meta, to = prefs)
        assertEquals("10.0.0.2", prefs.map[META_SERVER_HOST])
        assertEquals("9000", prefs.map[META_SERVER_PORT])
        assertNull(meta.get(META_SERVER_HOST))
    }

    @Test fun migration_without_address_does_nothing() = runTest {
        val prefs = FakeKeyValueStore()
        migrateServerAddress(from = FakeKeyValueStore(), to = prefs)
        assertEquals(emptyMap<String, String?>(), prefs.map)
    }
}
