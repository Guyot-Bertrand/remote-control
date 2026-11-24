package fr.remotecontrol.data.sync

import android.util.Log
import fr.remotecontrol.net.ServerEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription

class SyncCoordinator(
    private val sync: SyncRepository,
    private val events: SharedFlow<ServerEvent>,
    private val log: (String, Throwable) -> Unit = { msg, t -> Log.w("SyncCoordinator", msg, t) },
) {
    /**
     * Collects until cancelled; syncs when the server revision differs from the local one.
     * Starts with one incremental sync when a local revision exists: after a short background,
     * the shared event stream is still connected, so no new Hello arrives for the events missed meanwhile.
     * That sync runs once subscribed: events emitted during it wait in the shared flow instead of being lost.
     */
    suspend fun run() {
        events.onSubscription { guarded { if (sync.localRevision() != null) sync.sync() } }.collect { event ->
            guarded { handle(event) }
        }
    }

    /** Une erreur (base illisible…) est journalisée : le coordinateur continue avec l'événement suivant. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("synchronisation en échec", e)
        }
    }

    private suspend fun handle(event: ServerEvent) {
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
