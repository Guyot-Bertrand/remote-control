package fr.remotecontrol.ui.remote

import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.PlayerSnapshotDto
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Touches de volume du téléphone : tant qu'un [handler] est inscrit (une lecture est en cours, sur n'importe
 * quel écran), elles sont consommées et réglées sur la télé ; sinon elles passent au système.
 * [log] : traces de diagnostic, pour `adb logcat -s rc.volume` (voir [logcat]).
 */
class VolumeKeys(val log: (String) -> Unit = {}) {
    @Volatile var handler: ((up: Boolean) -> Unit)? = null

    /**
     * Une touche reçue par l'activité (`dispatchKeyEvent`, avant les vues) ; vrai si elle est consommée.
     * Avec un gestionnaire : chaque `ACTION_DOWN` (répétitions d'un appui maintenu comprises) fait un pas ;
     * `ACTION_DOWN` et `ACTION_UP` sont consommés, pour que le téléphone n'affiche pas son curseur.
     */
    fun dispatch(keyCode: Int, action: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val h = handler
        log("touche ${keyName(keyCode)} ${actionName(action)} gestionnaire=${if (h != null) "oui" else "non"}")
        if (h == null) return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> true.also { h(keyCode == KeyEvent.KEYCODE_VOLUME_UP) }
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    private fun keyName(keyCode: Int) = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "VOLUME_UP" else "VOLUME_DOWN"

    private fun actionName(action: Int) = when (action) {
        KeyEvent.ACTION_DOWN -> "DOWN"
        KeyEvent.ACTION_UP -> "UP"
        else -> "action=$action"
    }

    companion object {
        const val TAG = "rc.volume"

        /** Traces dans logcat (hors tests JVM : `android.util.Log` n'y est pas disponible). */
        val logcat: (String) -> Unit = { Log.d(TAG, it) }
    }
}

/**
 * Vrai tant qu'une lecture est connue (pause comprise, pas terminée) et que le serveur est connecté ;
 * chaque changement de l'un ou l'autre est tracé dans [log].
 */
fun volumeKeysActive(
    snapshot: Flow<PlayerSnapshotDto?>,
    server: Flow<ServerState>,
    log: (String) -> Unit,
): Flow<Boolean> = combine(snapshot, server) { s, srv ->
    val st = s?.state
    (st != null && st.running && !st.ended) to (srv is ServerState.Connected)
}
    .distinctUntilChanged()
    .onEach { (playing, connected) -> log("lecture=${yesNo(playing)} connecté=${yesNo(connected)}") }
    .map { (playing, connected) -> playing && connected }
    .distinctUntilChanged()

private fun yesNo(b: Boolean) = if (b) "oui" else "non"

/**
 * Inscrit [onStep] dans [keys] tant que [lifecycle] est `RESUMED` et que [running] vaut vrai ; le retire sinon.
 * Chaque inscription et chaque retrait sont tracés avec leur raison.
 */
suspend fun registerVolumeKeysWhileResumed(
    lifecycle: Lifecycle,
    running: Flow<Boolean>,
    keys: VolumeKeys,
    onStep: (up: Boolean) -> Unit,
) {
    val mine: (Boolean) -> Unit = { onStep(it) }
    fun remove(reason: String) {
        if (keys.handler !== mine) return
        keys.handler = null
        keys.log("retiré : $reason")
    }
    try {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            running.collectLatest { on ->
                if (!on) {
                    remove("plus de lecture ou connexion")
                    return@collectLatest
                }
                keys.handler = mine
                keys.log("inscrit (lecture en cours, connecté, activité au premier plan)")
                try {
                    awaitCancellation()
                } finally {
                    // Annulé par une nouvelle valeur de [running] (traitée ci-dessus) ou par le cycle de vie.
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) remove("cycle de vie (${lifecycle.currentState})")
                }
            }
        }
    } finally {
        remove("cycle de vie (${lifecycle.currentState})")
    }
}
