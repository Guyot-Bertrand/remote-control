package fr.remotecontrol

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import fr.remotecontrol.ui.common.buildImageLoader
import kotlinx.coroutines.launch

class RcApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Au niveau du processus (et non de l'activité) : une rotation n'interrompt ni la
        // découverte ni la synchronisation. La synchronisation initiale passe uniquement
        // par SyncCoordinator, sur le Hello du WebSocket. Au premier plan seulement : le
        // serveur est revérifié à chaque retour, recherché toutes les 20 s s'il manque, et
        // revérifié quand le WebSocket échoue.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            container.migrateSettings()
            ProcessLifecycleOwner.get().repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.coordinator.run() }
                launch { container.tvStatus.run() }
                container.locator.monitor(container.eventsClient.failures)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context, container.okHttp)
}
