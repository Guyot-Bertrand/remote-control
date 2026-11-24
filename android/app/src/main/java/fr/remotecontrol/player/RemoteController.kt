package fr.remotecontrol.player

import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * État du lecteur pour la télécommande : d'abord `GET /api/player/state`, puis chaque `hello.player` et chaque
 * événement `player`. Les événements sont écoutés avant la requête : si l'un d'eux arrive avant la réponse,
 * il est plus récent qu'elle, et la réponse est alors ignorée. Sans abonné depuis 5 s, le flux s'arrête et
 * oublie le dernier état : une réouverture ne rejoue jamais une lecture passée.
 */
class RemoteController(api: RcApi, events: Flow<ServerEvent>, scope: CoroutineScope) {
    val snapshot: StateFlow<PlayerSnapshotDto?> = channelFlow {
        val lock = Mutex()
        var fromEvents = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { e ->
                val s = when (e) {
                    is ServerEvent.Hello -> e.player
                    is ServerEvent.Player -> e.snapshot
                    else -> return@collect
                }
                lock.withLock {
                    fromEvents = true
                    send(s)
                }
            }
        }
        // Échec silencieux voulu : l'état arrive aussi par `hello` dès que le WebSocket est connecté, et l'écran
        // reste « en chargement » d'ici là ; l'absence de serveur est déjà signalée par la pastille de connexion.
        val initial = api.playerState()
        if (initial is ApiResult.Ok) lock.withLock { if (!fromEvents) send(initial.value) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
}
