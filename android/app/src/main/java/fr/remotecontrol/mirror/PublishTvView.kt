package fr.remotecontrol.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fr.remotecontrol.net.TvViewDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

/** Miroir de la télé, fourni par `MainActivity` ; nul (rien n'est publié) hors de l'appli, par exemple en aperçu. */
val LocalTvMirror = staticCompositionLocalOf<TvMirror?> { null }

/**
 * Publie [view] sur la télé tant que l'écran est au premier plan (`RESUMED`), à chaque changement, et de nouveau
 * au retour sur l'écran. [view] nulle (chargement) : rien n'est publié, la télé garde la vue précédente.
 */
@Composable
fun PublishTvView(view: TvViewDto?) {
    val mirror = LocalTvMirror.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(view)
    LaunchedEffect(mirror, lifecycle) {
        publishWhileResumed(lifecycle, snapshotFlow { current }, mirror::publish)
    }
}

/** Logique de [PublishTvView] : chaque vue non nulle de [views] est publiée tant que [lifecycle] est `RESUMED`. */
suspend fun publishWhileResumed(lifecycle: Lifecycle, views: Flow<TvViewDto?>, publish: (TvViewDto) -> Unit) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        views.filterNotNull().collect(publish)
    }
}
