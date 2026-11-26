package fr.remotecontrol.player

import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.RcApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Délai maximal pendant lequel un volume envoyé reste affiché en attendant le serveur. */
private const val HOLD_MS = 2_000L
private const val VOLUME_TOLERANCE = 2
private const val VOLUME_STEP = 5

/** Un appui sur une touche de volume ; [seq] le distingue d'un appui précédent de même niveau. */
private data class VolumeShot(val level: Int, val seq: Long)

/**
 * Touches de volume du téléphone réglées sur la télé, quel que soit l'écran : chaque appui vise ±5 (de 0 à 100)
 * à partir de la cible en attente, sinon du dernier volume connu ([snapshot]). Les envois sont sérialisés et
 * regroupés : au plus un par [sendIntervalMs], compté après la réponse ; la dernière valeur gagne.
 */
class VolumeStepper(
    private val api: RcApi,
    private val snapshot: StateFlow<PlayerSnapshotDto?>,
    private val scope: CoroutineScope,
    private val sendIntervalMs: Long = 250L,
) {
    private val _pending = MutableStateFlow<Int?>(null)

    /**
     * Volume visé, tenu jusqu'à ce que le serveur l'atteigne (à la tolérance près) ou au plus [HOLD_MS] ;
     * relâché tout de suite si l'envoi échoue. Nul sans appui en cours.
     */
    val pending: StateFlow<Int?> = _pending.asStateFlow()

    private val _failures = MutableSharedFlow<ApiError>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Échecs d'envoi, pour le message d'erreur. */
    val failures: SharedFlow<ApiError> = _failures.asSharedFlow()

    /** Cible pas encore envoyée : la dernière valeur gagne. */
    private val target = MutableStateFlow<VolumeShot?>(null)
    private var seq = 0L

    init {
        scope.launch {
            target.filterNotNull().collect { shot ->
                send(shot.level)
                delay(sendIntervalMs)
                target.compareAndSet(shot, null)
            }
        }
    }

    /** Un appui ; sans volume connu, il est ignoré. */
    fun step(up: Boolean) {
        val base = _pending.value ?: snapshot.value?.state?.volume ?: return
        val level = (base + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0, 100)
        _pending.value = level
        // Numéro d'appui : une même valeur redemandée est quand même envoyée (et tenue).
        target.value = VolumeShot(level, ++seq)
    }

    /** Rend la main dès la réponse ; l'attente du serveur se poursuit à part. */
    private suspend fun send(level: Int) {
        val r = api.volume(level)
        if (r is ApiResult.Err) _failures.tryEmit(r.error)
        scope.launch {
            if (r is ApiResult.Ok) {
                withTimeoutOrNull(HOLD_MS) { snapshot.first { it != null && abs(it.state.volume - level) <= VOLUME_TOLERANCE } }
            }
            _pending.update { if (it == level) null else it }
        }
    }
}
