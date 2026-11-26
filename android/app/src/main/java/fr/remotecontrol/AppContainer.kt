package fr.remotecontrol

import android.content.Context
import fr.remotecontrol.data.CatalogFilter
import fr.remotecontrol.data.KeyValueStore
import fr.remotecontrol.data.MetaKeyValueStore
import fr.remotecontrol.data.SharedPreferencesKeyValueStore
import fr.remotecontrol.data.migrateServerAddress
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.buildDatabase
import fr.remotecontrol.data.sync.SyncCoordinator
import fr.remotecontrol.data.sync.SyncRepository
import fr.remotecontrol.discovery.NsdServerDiscovery
import fr.remotecontrol.discovery.ServerLocator
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.mirror.TvMirror
import fr.remotecontrol.net.EventsClient
import fr.remotecontrol.net.OkHttpRcApi
import fr.remotecontrol.net.ServerEvent
import fr.remotecontrol.net.rcOkHttpClient
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.player.TvStatus
import fr.remotecontrol.player.liveWhileConnected
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.ui.remote.VolumeKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val okHttp: OkHttpClient = rcOkHttpClient()
    val db: AppDatabase = buildDatabase(appContext)
    val catalogDao: CatalogDao = db.catalogDao()
    val store: KeyValueStore =
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences("rc-settings", Context.MODE_PRIVATE))

    /** Une fois au démarrage : l'adresse mémorisée par les premières versions (dans le cache Room) passe aux préférences. */
    suspend fun migrateSettings() = migrateServerAddress(from = MetaKeyValueStore(db.metaDao()), to = store)

    // api et locator dépendent l'un de l'autre : tous deux paresseux.
    val locator: ServerLocator by lazy { ServerLocator(store, api, NsdServerDiscovery(appContext)) }
    val api: OkHttpRcApi by lazy { OkHttpRcApi(okHttp, { locator.currentBaseUrl() }) }

    val eventsClient: EventsClient = EventsClient(okHttp, { locator.currentBaseUrl() })
    val events: SharedFlow<ServerEvent> =
        eventsClient.events().shareIn(appScope, SharingStarted.WhileSubscribed(5_000))
    val sync: SyncRepository = SyncRepository(db, api)
    val coordinator: SyncCoordinator = SyncCoordinator(sync, events)
    val tvStatus: TvStatus = TvStatus(events)
    val tvMirror: TvMirror by lazy {
        TvMirror(api, store, connected = locator.state.map { it is ServerState.Connected })
    }
    /** Touches de volume du téléphone, détournées vers la télé quand la télécommande est affichée en lecture. */
    val volumeKeys: VolumeKeys = VolumeKeys()
    val filter: CatalogFilter = CatalogFilter()
    val playLauncher: PlayLauncher by lazy { PlayLauncher(api, locator::reportPlayerMissing) }
    val remote: RemoteController by lazy { RemoteController(api, events, appScope) }

    /** Lecture en cours pour les fiches : inconnue tant que le serveur n'est pas connecté. */
    val liveForSheets: Flow<PlayerSnapshotDto?> by lazy { liveWhileConnected(remote.snapshot, locator.state) }

    /** « Réessayer » du bandeau « lecteur absent » : nouvelle sonde du serveur. */
    fun retryPlayer() {
        appScope.launch { locator.refresh() }
    }
}
