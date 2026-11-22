package fr.remotecontrol.data

import fr.remotecontrol.data.db.MetaDao
import fr.remotecontrol.data.db.MetaEntity

const val META_REVISION = "sync.revision"
const val META_SERVER_HOST = "server.host"
const val META_SERVER_PORT = "server.port"
const val META_SERVER_MANUAL = "server.manual"

interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
}

class MetaKeyValueStore(private val dao: MetaDao) : KeyValueStore {
    override suspend fun get(key: String): String? = dao.get(key)

    override suspend fun put(key: String, value: String?) {
        if (value == null) dao.delete(key) else dao.put(MetaEntity(key, value))
    }
}
