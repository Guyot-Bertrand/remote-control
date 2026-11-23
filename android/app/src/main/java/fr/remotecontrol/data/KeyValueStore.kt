package fr.remotecontrol.data

import android.content.SharedPreferences
import fr.remotecontrol.data.db.MetaDao
import fr.remotecontrol.data.db.MetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val META_REVISION = "sync.revision"
const val META_SERVER_HOST = "server.host"
const val META_SERVER_PORT = "server.port"
const val META_SERVER_MANUAL = "server.manual"

interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
}

/** Réglages durables (adresse du serveur) : hors du cache Room, qui peut être effacé à tout moment. */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun put(key: String, value: String?) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
        }
    }
}

private val SERVER_KEYS = listOf(META_SERVER_HOST, META_SERVER_PORT, META_SERVER_MANUAL)

/**
 * Déplace l'adresse du serveur de l'ancien emplacement (table `meta` de Room) vers les préférences, sans
 * écraser une adresse déjà présente ; l'ancienne copie est toujours effacée.
 */
suspend fun migrateServerAddress(from: KeyValueStore, to: KeyValueStore) {
    if (from.get(META_SERVER_HOST) == null) return
    if (to.get(META_SERVER_HOST) == null) SERVER_KEYS.forEach { key -> from.get(key)?.let { to.put(key, it) } }
    SERVER_KEYS.forEach { from.put(it, null) }
}

class MetaKeyValueStore(private val dao: MetaDao) : KeyValueStore {
    override suspend fun get(key: String): String? = dao.get(key)

    override suspend fun put(key: String, value: String?) {
        if (value == null) dao.delete(key) else dao.put(MetaEntity(key, value))
    }
}
