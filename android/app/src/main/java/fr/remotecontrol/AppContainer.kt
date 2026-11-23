package fr.remotecontrol

import android.content.Context
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.data.MetaKeyValueStore
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.buildDatabase
import fr.remotecontrol.data.sync.SyncCoordinator
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.discovery.NsdServerDiscovery
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.net.EventsClient
import fr.remotecontrol.net.OkHttpRcApi
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.rcOkHttpClient
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.player.TvStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val okHttp: OkHttpClient = rcOkHttpClient()
    val db: AppDatabase = buildDatabase(appContext)
    val catalogDao: CatalogDao = db.catalogDao()
    val store: KeyValueStore = MetaKeyValueStore(db.metaDao())

    // api et locator dépendent l'un de l'autre : tous deux paresseux.
    val locator: ServerLocator by lazy { ServerLocator(store, api, NsdServerDiscovery(appContext)) }
    val api: OkHttpRcApi by lazy { OkHttpRcApi(okHttp, { locator.currentBaseUrl() }) }

    val eventsClient: EventsClient = EventsClient(okHttp, { locator.currentBaseUrl() })
    val events: SharedFlow<ServerEvent> =
        eventsClient.events().shareIn(appScope, SharingStarted.WhileSubscribed(5_000))
    val sync: SyncRepository = SyncRepository(db, api)
    val coordinator: SyncCoordinator = SyncCoordinator(sync, events)
    val tvStatus: TvStatus = TvStatus(events)
    val filter: CatalogFilter = CatalogFilter()
    val playLauncher: PlayLauncher by lazy { PlayLauncher(api) }
    val remote: RemoteController by lazy { RemoteController(api, events, appScope) }
}
