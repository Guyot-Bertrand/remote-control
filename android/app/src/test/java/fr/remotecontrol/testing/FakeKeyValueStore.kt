package fr.remotecontrol.testing

import fr.remotecontrol.data.KeyValueStore

class FakeKeyValueStore(val map: MutableMap<String, String?> = mutableMapOf()) : KeyValueStore {
    override suspend fun get(key: String): String? = map[key]
    override suspend fun put(key: String, value: String?) {
        map[key] = value
    }
}
