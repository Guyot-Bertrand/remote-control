package fr.remotecontrol.data.sync

import fr.remotecontrol.net.ServerEvent
import kotlinx.coroutines.flow.Flow

class SyncCoordinator(
    private val sync: SyncRepository,
    private val events: Flow<ServerEvent>,
) {
    /**
     * Collects until cancelled; syncs when the server revision differs from the local one.
     * Starts with one incremental sync when a local revision exists: after a short background,
     * the shared event stream is still connected, so no new Hello arrives for the events missed meanwhile.
     */
    suspend fun run() {
        if (sync.localRevision() != null) sync.sync()
        events.collect { event ->
            val local = sync.localRevision()
            when (event) {
                // Hello: a lower server revision means a reset server, so resync fully.
                is ServerEvent.Hello -> when {
                    local == null || event.revision < local -> sync.sync(forceFull = true)
                    event.revision > local -> sync.sync()
                }
                // Catalog: r <= local is a stale buffered event and is ignored.
                is ServerEvent.Catalog -> when {
                    local == null -> sync.sync(forceFull = true)
                    event.revision > local -> sync.sync()
                }
                else -> Unit
            }
        }
    }
}
