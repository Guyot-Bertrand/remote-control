package fr.remotecontrol.mirror

import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.TvViewDto
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val PHONE_ID_KEY = "tv.phone_id"

/**
 * Envoie au serveur la vue affichée par le téléphone, pour que la télé la reproduise (arbitrage 14).
 * Au premier plan seulement ([run]), au plus un envoi par [minInterval], et seule la dernière vue en
 * attente est envoyée. Un échec est silencieux : le prochain changement renvoie la vue.
 * Rien n'est envoyé tant que le serveur n'est pas joignable ([connected] faux) ; à chaque connexion, la dernière
 * vue est renvoyée une fois (démarrage à froid, coupure du Wi-Fi, redémarrage du serveur).
 */
class TvMirror(
    private val api: RcApi,
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minInterval: Duration = 500.milliseconds,
    private val connected: Flow<Boolean> = flowOf(true),
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

    /**
     * À appeler au premier plan : renvoie d'abord la dernière vue connue (dès que le serveur est joignable), puis
     * chaque nouvelle vue. Une vue identique à la dernière envoyée avec succès n'est pas renvoyée (l'écran republie
     * la sienne au retour au premier plan) ; ce souvenir est oublié à chaque connexion, et d'un [run] à l'autre.
     */
    suspend fun run(): Unit = coroutineScope {
        val online = AtomicBoolean(false)
        val lastSent = AtomicReference<TvViewDto?>(null)
        launch {
            connected.distinctUntilChanged().collect { up ->
                online.set(up)
                if (up) {
                    lastSent.set(null)
                    pending.set(last.get())
                    wake.trySend(Unit)
                }
            }
        }
        while (true) {
            wake.receive()
            // Hors connexion, la vue en attente reste : la connexion la remplacera par la dernière vue publiée.
            if (!online.get()) continue
            val view = pending.getAndSet(null) ?: continue
            if (view == lastSent.get()) continue
            if (send(view)) lastSent.set(view)
            delay(minInterval)
        }
    }

    /** Vrai si le serveur a répondu (vue reçue). */
    private suspend fun send(view: TvViewDto): Boolean {
        val id = phoneId ?: (store.get(PHONE_ID_KEY) ?: UUID.randomUUID().toString().also { store.put(PHONE_ID_KEY, it) })
            .also { phoneId = it }
        lastSeq = maxOf(clock(), lastSeq + 1)
        return api.tvView(view.copy(phoneId = id, seq = lastSeq)) is ApiResult.Ok
    }
}
