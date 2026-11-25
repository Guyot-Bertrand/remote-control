package fr.remotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.mirror.LocalTvMirror
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.ui.browse.BrowseScreen
import fr.remotecontrol.ui.browse.BrowseViewModel
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.HomeRoute
import fr.remotecontrol.ui.common.LocalServerBaseUrl
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.PrePlayRoute
import fr.remotecontrol.ui.common.MiniPlayer
import fr.remotecontrol.ui.common.RcSnackbar
import fr.remotecontrol.ui.common.RcBottomBar
import fr.remotecontrol.ui.common.RemoteRoute
import fr.remotecontrol.ui.common.SORT_RECENT
import fr.remotecontrol.ui.common.ScanRoute
import fr.remotecontrol.ui.common.SearchRoute
import fr.remotecontrol.ui.common.SeasonRoute
import fr.remotecontrol.ui.common.SettingsRoute
import fr.remotecontrol.ui.common.TopDestination
import fr.remotecontrol.ui.common.WorkRoute
import fr.remotecontrol.ui.common.shownTab
import fr.remotecontrol.ui.common.topNavigation
import fr.remotecontrol.ui.home.HomeScreen
import fr.remotecontrol.ui.home.HomeViewModel
import fr.remotecontrol.ui.preplay.PrePlayArgs
import fr.remotecontrol.ui.preplay.PrePlayScreen
import fr.remotecontrol.ui.preplay.PrePlayViewModel
import fr.remotecontrol.ui.preplay.toArgs
import fr.remotecontrol.ui.preplay.toRoute
import fr.remotecontrol.ui.remote.NowPlayingViewModel
import fr.remotecontrol.ui.remote.RemoteScreen
import fr.remotecontrol.ui.remote.RemoteViewModel
import fr.remotecontrol.ui.scan.ScanScreen
import fr.remotecontrol.ui.scan.ScanViewModel
import fr.remotecontrol.ui.search.SearchScreen
import fr.remotecontrol.ui.season.SeasonScreen
import fr.remotecontrol.ui.season.SeasonViewModel
import fr.remotecontrol.ui.search.SearchViewModel
import fr.remotecontrol.ui.settings.SettingsScreen
import fr.remotecontrol.ui.settings.SettingsViewModel
import fr.remotecontrol.ui.theme.RcColors
import fr.remotecontrol.ui.theme.RcTheme
import fr.remotecontrol.ui.work.WorkScreen
import fr.remotecontrol.ui.work.WorkViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RcApp).container
        setContent {
            val server by container.locator.state.collectAsStateWithLifecycle()
            RcTheme {
                CompositionLocalProvider(
                    LocalServerBaseUrl provides (server as? ServerState.Connected)?.address?.baseUrl,
                    LocalTvMirror provides container.tvMirror,
                ) {
                    Surface(Modifier.fillMaxSize(), color = RcColors.bg, contentColor = RcColors.text) {
                        RcScaffold(container)
                    }
                }
            }
        }
    }
}

/** Destinations de premier niveau : celles qui affichent la barre de navigation. */
private fun NavDestination.topLevel(): TopDestination? = when {
    hasRoute<HomeRoute>() -> TopDestination.Home
    hasRoute<BrowseRoute>() -> TopDestination.Browse
    hasRoute<SearchRoute>() -> TopDestination.Search
    hasRoute<ScanRoute>() -> TopDestination.Scan
    else -> null
}

private fun NavController.navigateTop(d: TopDestination, current: TopDestination?) {
    val target = topNavigation(d, current)
    navigate(target.route) {
        if (target.reset) {
            // Re-toucher « Parcourir » : on retire le parcours affiché (sans le sauver) et on repart du défaut.
            popUpTo<HomeRoute>()
        } else {
            popUpTo<HomeRoute> { saveState = true }
            launchSingleTop = true
            restoreState = target.restore
        }
    }
}

@Composable
private fun RcScaffold(c: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val top = entry?.destination?.topLevel()
    var lastTab by rememberSaveable { mutableStateOf(TopDestination.Home) }
    val shown = shownTab(top, lastTab)
    LaunchedEffect(shown) { lastTab = shown }
    // Lecture en cours : bandeau « Sur la télé » en bas de tous les écrans (fiches comprises), sauf la
    // télécommande elle-même, l'écran de choix avant lecture et le scanner (plein écran).
    val miniPlayer = entry?.destination?.let { d ->
        !d.hasRoute<RemoteRoute>() && !d.hasRoute<PrePlayRoute>() && !d.hasRoute<ScanRoute>()
    } == true
    Column(Modifier.fillMaxSize()) {
        val insets = if (top != null || miniPlayer) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        } else {
            Modifier.safeDrawingPadding()
        }
        Box(Modifier.weight(1f).then(insets)) { RcNavHost(c, nav) }
        if (miniPlayer) NowPlaying(c) { nav.navigate(RemoteRoute) { launchSingleTop = true } }
        if (top != null) {
            RcBottomBar(selected = shown, onSelect = {
                lastTab = it
                nav.navigateTop(it, shown)
            })
        } else if (miniPlayer) {
            // Sans barre de navigation : le bandeau (ou le clavier) reste au-dessus de la barre système.
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

@Composable
private fun NowPlaying(c: AppContainer, onOpen: () -> Unit) {
    val vm = viewModel { NowPlayingViewModel(c.api, c.remote, c.catalogDao::playingInfo) }
    val state by vm.state.collectAsStateWithLifecycle()
    state.message?.let { RcSnackbar(it, vm::clearMessage, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
    state.player?.let { MiniPlayer(it, onOpen, vm::togglePause) }
}

@Composable
private fun RcNavHost(c: AppContainer, nav: NavHostController) {
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val vm = viewModel { HomeViewModel(c.catalogDao, c.filter, c.locator, c.sync) }
            val state by vm.state.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                playerMissing = c.playerMissing(),
                onRetryPlayer = c::retryPlayer,
                onPlayableOnly = vm::setPlayableOnly,
                onOpenWork = { nav.navigate(WorkRoute(it)) },
                onOpenSeason = { nav.navigate(SeasonRoute(it)) },
                onOpenType = { nav.navigate(BrowseRoute(type = it)) },
                onOpenCollection = { nav.navigate(BrowseRoute(collectionId = it)) },
                onSeeAllRecent = { nav.navigate(BrowseRoute(sort = SORT_RECENT)) },
                onRemote = { nav.navigate(RemoteRoute) { launchSingleTop = true } },
                onSettings = { nav.navigate(SettingsRoute) },
                onResync = vm::resync,
            )
        }
        composable<SettingsRoute> {
            val vm = viewModel { SettingsViewModel(c.locator, c.sync, c.catalogDao) }
            val state by vm.state.collectAsStateWithLifecycle()
            val now by vm.now.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                now = now,
                onManualInput = vm::onManualInput,
                onSaveManual = vm::saveManual,
                onRediscover = vm::rediscover,
                onResync = vm::resync,
                onDismissMessage = vm::clearMessage,
                onBack = { nav.popBackStack() },
            )
        }
        composable<BrowseRoute> { entry ->
            val route = entry.toRoute<BrowseRoute>()
            val vm = viewModel { BrowseViewModel(route, c.catalogDao, c.filter) }
            val state by vm.state.collectAsStateWithLifecycle()
            val tvView by vm.tvView.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            BrowseScreen(
                state = state,
                tvView = tvView,
                server = server,
                onPlayableOnly = vm::setPlayableOnly,
                onType = vm::setType,
                onToggleSort = vm::toggleSort,
                onFirstVisible = vm::onFirstVisible,
                onOpen = { nav.navigate(WorkRoute(it.id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<SearchRoute> {
            val vm = viewModel { SearchViewModel(c.catalogDao, c.filter) }
            val state by vm.state.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            SearchScreen(
                state = state,
                server = server,
                onQuery = vm::onQuery,
                onPlayableOnly = vm::setPlayableOnly,
                onOpen = { nav.navigate(WorkRoute(it.id)) },
            )
        }
        composable<WorkRoute> { entry ->
            val id = entry.toRoute<WorkRoute>().id
            val vm = viewModel { WorkViewModel(id, c.catalogDao, c.playLauncher, c.tvStatus, c.liveForSheets, c.locator.playerMissing) { c.locator.locate(force = true) } }
            val state by vm.state.collectAsStateWithLifecycle()
            val missing by vm.missing.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect(nav::onNavEvent) }
            WorkScreen(
                state = state,
                missing = missing,
                server = server,
                onPlayFromStart = vm::playFromStart,
                onResume = vm::resume,
                onPrePlay = { nav.navigate(PrePlayArgs(PlayTarget(TargetKind.Work, id), it).toRoute()) },
                onOpenRemote = { nav.navigate(RemoteRoute) { launchSingleTop = true } },
                onOpenSeason = { nav.navigate(SeasonRoute(it)) },
                onOpenCollection = { nav.navigate(BrowseRoute(collectionId = it)) },
                onDismissMessage = vm::clearMessage,
                onRetryServer = vm::retryServer,
                onRetryPlayer = c::retryPlayer,
                onBack = { nav.popBackStack() },
            )
        }
        composable<SeasonRoute> { entry ->
            val id = entry.toRoute<SeasonRoute>().id
            val vm = viewModel { SeasonViewModel(id, c.catalogDao, c.playLauncher, c.tvStatus, c.liveForSheets, c.locator.playerMissing, createSavedStateHandle()) }
            val state by vm.state.collectAsStateWithLifecycle()
            val missing by vm.missing.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect(nav::onNavEvent) }
            SeasonScreen(
                state = state,
                missing = missing,
                server = server,
                onPlay = vm::play,
                onSelect = vm::select,
                onPrePlay = { episode, mode -> nav.navigate(PrePlayArgs(PlayTarget(TargetKind.Episode, episode), mode).toRoute()) },
                onOpenRemote = { nav.navigate(RemoteRoute) { launchSingleTop = true } },
                // Changer de saison remplace la fiche affichée (le retour ramène à la série).
                onOpenSeason = { nav.navigate(SeasonRoute(it)) { popUpTo<SeasonRoute> { inclusive = true } } },
                onDismissMessage = vm::clearMessage,
                onRetryPlayer = c::retryPlayer,
                onBack = { nav.popBackStack() },
            )
        }
        composable<PrePlayRoute> { entry ->
            val args = entry.toRoute<PrePlayRoute>().toArgs()
            // Un ViewModel par entrée de navigation : les pistes sont relues à chaque ouverture.
            val vm = viewModel { PrePlayViewModel(args, c.catalogDao, c.api, c.playLauncher) }
            val state by vm.state.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    if (event == NavEvent.ToRemote) {
                        // L'écran de choix quitte la pile : le retour depuis la télécommande ramène à la fiche.
                        nav.navigate(RemoteRoute) {
                            popUpTo<PrePlayRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        nav.onNavEvent(event)
                    }
                }
            }
            PrePlayScreen(
                state = state,
                target = args.target,
                server = server,
                playerMissing = c.playerMissing(),
                onRetryPlayer = c::retryPlayer,
                onVersion = vm::selectVersion,
                onAudio = vm::selectAudio,
                onSubtitle = vm::selectSubtitle,
                onRetry = vm::retry,
                onConfirm = vm::confirm,
                onClose = { nav.popBackStack() },
            )
        }
        composable<RemoteRoute> {
            val vm = viewModel { RemoteViewModel(c.api, c.remote, c.catalogDao::playingInfo) }
            val state by vm.state.collectAsStateWithLifecycle()
            val server by c.locator.state.collectAsStateWithLifecycle()
            RemoteScreen(
                state = state,
                server = server,
                onTogglePause = vm::togglePause,
                onRestart = vm::restart,
                onSkip = vm::skip,
                onSeekDrag = vm::onSeekDrag,
                onSeekRelease = vm::onSeekRelease,
                onVolumeDrag = vm::onVolumeDrag,
                onVolumeRelease = vm::onVolumeRelease,
                onAudio = vm::selectAudio,
                onSubtitle = vm::selectSubtitle,
                onStop = vm::stop,
                onOpenSheet = { nav.openSheet(state.workId, state.seasonId) },
                onDismissMessage = vm::clearMessage,
                onBack = { nav.popBackStack() },
            )
        }
        composable<ScanRoute> {
            val vm = viewModel { ScanViewModel(c.catalogDao, c.sync) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                // Retour sur l'écran (depuis la fiche ouverte) : l'analyse reprend.
                vm.rearm()
                vm.events.collect(nav::onNavEvent)
            }
            ScanScreen(state = state, onDecoded = vm::onDecoded, onDismissMessage = vm::clearMessage)
        }
    }
}

/** Bandeau « lecteur absent du PC » : suivi à chaque sonde du serveur ([fr.remotecontrol.discovery.ServerLocator]). */
@Composable
private fun AppContainer.playerMissing(): Boolean {
    val missing by locator.playerMissing.collectAsStateWithLifecycle(initialValue = false)
    return missing
}

/**
 * Fin de lecture : retour à la fiche (saison d'un épisode, œuvre sinon). Si elle est déjà sous la
 * télécommande dans la pile, on y revient ; sinon on l'ouvre à la place de la télécommande.
 */
private fun NavController.openSheet(workId: Long?, seasonId: Long?) {
    val route: Any = seasonId?.let(::SeasonRoute) ?: workId?.let(::WorkRoute) ?: return
    if (popBackStack(route, inclusive = false)) return
    navigate(route) { popUpTo<RemoteRoute> { inclusive = true } }
}

private fun NavController.onNavEvent(event: NavEvent) = when (event) {
    NavEvent.ToRemote -> navigate(RemoteRoute) { launchSingleTop = true }
    is NavEvent.ToWork -> navigate(WorkRoute(event.id))
    is NavEvent.ToSeason -> navigate(SeasonRoute(event.id))
}
