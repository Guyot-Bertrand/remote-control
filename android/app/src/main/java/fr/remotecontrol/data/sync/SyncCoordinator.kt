package fr.remotecontrol.data.sync

import fr.remotecontrol.net.ServerEvent
import kotlinx.coroutines.flow.Flow

class SyncCoordinator(
    private val sync: SyncRepository,
    private val events: Flow<ServerEvent>,
) {
    /** Collects until cancelled; syncs when the server revision differs from the local one. */
    suspend fun run() {
        events.collect { event ->
            val r = when (event) {
                is ServerEvent.Hello -> event.revision
                is ServerEvent.Catalog -> event.revision
                else -> return@collect
            }
            val local = sync.localRevision()
            when {
                local == null || r < local -> sync.sync(forceFull = true)
                r > local -> sync.sync()
            }
        }
    }
}
