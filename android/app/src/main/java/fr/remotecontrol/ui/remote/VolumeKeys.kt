package fr.remotecontrol.ui.remote

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Touches de volume du téléphone : tant qu'un [handler] est inscrit (télécommande affichée pendant une lecture),
 * elles sont consommées et réglées sur la télé ; sinon elles passent au système.
 */
class VolumeKeys {
    @Volatile var handler: ((up: Boolean) -> Unit)? = null

    private fun isVolume(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** Les répétitions d'un appui maintenu arrivent comme de nouveaux appuis. */
    fun onKeyDown(keyCode: Int): Boolean {
        val h = handler
        if (h == null || !isVolume(keyCode)) return false
        h(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        return true
    }

    /** Consomme le relâchement, pour que le téléphone n'affiche pas son curseur. */
    fun onKeyUp(keyCode: Int): Boolean = handler != null && isVolume(keyCode)
}

/** Inscrit [onStep] dans [keys] tant que [lifecycle] est `RESUMED` et que [running] vaut vrai ; le retire sinon. */
suspend fun registerVolumeKeysWhileResumed(
    lifecycle: Lifecycle,
    running: Flow<Boolean>,
    keys: VolumeKeys,
    onStep: (up: Boolean) -> Unit,
) {
    val mine: (Boolean) -> Unit = { onStep(it) }
    try {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            running.collectLatest { on ->
                if (!on) return@collectLatest
                keys.handler = mine
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    if (keys.handler === mine) keys.handler = null
                }
            }
        }
    } finally {
        if (keys.handler === mine) keys.handler = null
    }
}

@Composable
fun VolumeKeysWhileResumed(keys: VolumeKeys, running: Boolean, onStep: (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val step by rememberUpdatedState(onStep)
    val isRunning by rememberUpdatedState(running)
    LaunchedEffect(keys, lifecycle) {
        registerVolumeKeysWhileResumed(lifecycle, snapshotFlow { isRunning }, keys) { step(it) }
    }
}
