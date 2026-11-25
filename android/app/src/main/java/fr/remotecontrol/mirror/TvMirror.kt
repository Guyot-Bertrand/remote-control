package fr.remotecontrol.mirror

import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.TvViewDto
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

private const val PHONE_ID_KEY = "tv.phone_id"

/**
 * Envoie au serveur la vue affichée par le téléphone, pour que la télé la reproduise (arbitrage 14).
 * Au premier plan seulement ([run]), au plus un envoi par [minInterval], et seule la dernière vue en
 * attente est envoyée. Un échec est silencieux : le prochain changement renvoie la vue.
 */
class TvMirror(
    private val api: RcApi,
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minInterval: Duration = 500.milliseconds,
) {
    private val last = AtomicReference<TvViewDto?>(null)
    private val pending = AtomicReference<TvViewDto?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastSeq = -1L
    private var phoneId: String? = null

    /** Retient [view] ; elle sera envoyée par [run], en remplacement de toute vue encore en attente. */
    fun publish(view: TvViewDto) {
        last.set(view)
        pending.set(view)
        wake.trySend(Unit)
    }

    /** À appeler au premier plan : renvoie d'abord la dernière vue connue, puis chaque nouvelle vue. */
    suspend fun run() {
        pending.set(last.get())
        wake.trySend(Unit)
        while (true) {
            wake.receive()
            val view = pending.getAndSet(null) ?: continue
            send(view)
            delay(minInterval)
        }
    }

    private suspend fun send(view: TvViewDto) {
        val id = phoneId ?: (store.get(PHONE_ID_KEY) ?: UUID.randomUUID().toString().also { store.put(PHONE_ID_KEY, it) })
            .also { phoneId = it }
        lastSeq = maxOf(clock(), lastSeq + 1)
        api.tvView(view.copy(phoneId = id, seq = lastSeq))
    }
}
