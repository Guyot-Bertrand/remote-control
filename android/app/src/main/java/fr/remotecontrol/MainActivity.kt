package fr.remotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.HomeRoute
import fr.remotecontrol.ui.common.LocalServerBaseUrl
import fr.remotecontrol.ui.common.RcBottomBar
import fr.remotecontrol.ui.common.RemoteRoute
import fr.remotecontrol.ui.common.SORT_RECENT
import fr.remotecontrol.ui.common.ScanRoute
import fr.remotecontrol.ui.common.SearchRoute
import fr.remotecontrol.ui.common.SeasonRoute
import fr.remotecontrol.ui.common.SettingsRoute
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.TopDestination
import fr.remotecontrol.ui.common.WORK_TYPES
import fr.remotecontrol.ui.common.WorkRoute
import fr.remotecontrol.ui.home.HomeScreen
import fr.remotecontrol.ui.home.HomeViewModel
import fr.remotecontrol.ui.settings.SettingsScreen
import fr.remotecontrol.ui.settings.SettingsViewModel
import fr.remotecontrol.ui.theme.RcColors
import fr.remotecontrol.ui.theme.RcTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RcApp).container
        // La synchronisation initiale passe uniquement par SyncCoordinator (sur le Hello du WebSocket).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.locator.locate()
                container.coordinator.run()
            }
        }
        setContent {
            val server by container.locator.state.collectAsStateWithLifecycle()
            RcTheme {
                CompositionLocalProvider(LocalServerBaseUrl provides (server as? ServerState.Connected)?.address?.baseUrl) {
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

private fun NavController.navigateTop(d: TopDestination) {
    val route: Any = when (d) {
        TopDestination.Home -> HomeRoute
        TopDestination.Browse -> BrowseRoute(type = WORK_TYPES.first())
        TopDestination.Search -> SearchRoute
        TopDestination.Scan -> ScanRoute
    }
    navigate(route) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun RcScaffold(c: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val top = entry?.destination?.topLevel()
    Column(Modifier.fillMaxSize()) {
        val insets = if (top != null) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        } else {
            Modifier.safeDrawingPadding()
        }
        Box(Modifier.weight(1f).then(insets)) { RcNavHost(c, nav) }
        if (top != null) RcBottomBar(selected = top, onSelect = nav::navigateTop)
    }
}

@Composable
private fun RcNavHost(c: AppContainer, nav: NavHostController) {
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val vm = viewModel { HomeViewModel(c.catalogDao, c.filter, c.locator) }
            val state by vm.state.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                onPlayableOnly = vm::setPlayableOnly,
                onOpenWork = { nav.navigate(WorkRoute(it)) },
                onOpenSeason = { nav.navigate(SeasonRoute(it)) },
                onOpenType = { nav.navigate(BrowseRoute(type = it)) },
                onOpenCollection = { nav.navigate(BrowseRoute(collectionId = it)) },
                onSeeAllRecent = { nav.navigate(BrowseRoute(sort = SORT_RECENT)) },
                onRemote = { nav.navigate(RemoteRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<SettingsRoute> {
            val vm = viewModel { SettingsViewModel(c.locator, c.sync, c.catalogDao) }
            val state by vm.state.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                onManualInput = vm::onManualInput,
                onSaveManual = vm::saveManual,
                onRediscover = vm::rediscover,
                onResync = vm::resync,
                onBack = { nav.popBackStack() },
            )
        }
        // Écrans des tâches suivantes : titre provisoire.
        composable<BrowseRoute> { Placeholder(Texts.sectionBrowse) }
        composable<SearchRoute> { Placeholder(Texts.search) }
        composable<WorkRoute> { Placeholder(Texts.work) }
        composable<SeasonRoute> { Placeholder(Texts.season) }
        composable<RemoteRoute> { Placeholder(Texts.remote) }
        composable<ScanRoute> { Placeholder(Texts.scan) }
    }
}

@Composable
private fun Placeholder(title: String) {
    Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(16.dp))
}
