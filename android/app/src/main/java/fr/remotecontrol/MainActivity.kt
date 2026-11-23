package fr.remotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.ui.common.BrowseRoute
import fr.remotecontrol.ui.common.HomeRoute
import fr.remotecontrol.ui.common.LocalServerBaseUrl
import fr.remotecontrol.ui.common.RemoteRoute
import fr.remotecontrol.ui.common.ScanRoute
import fr.remotecontrol.ui.common.SearchRoute
import fr.remotecontrol.ui.common.SeasonRoute
import fr.remotecontrol.ui.common.SettingsRoute
import fr.remotecontrol.ui.common.Texts
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
                        Box(Modifier.safeDrawingPadding()) { RcNavHost(container) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RcNavHost(c: AppContainer) {
    val nav = rememberNavController()
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
                onSearch = { nav.navigate(SearchRoute) },
                onScan = { nav.navigate(ScanRoute) },
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
