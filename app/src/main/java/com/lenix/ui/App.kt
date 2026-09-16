package com.lenix.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lenix.ui.screens.DesktopScreen
import com.lenix.ui.screens.HomeScreen
import com.lenix.ui.screens.InstallScreen
import com.lenix.ui.screens.InstanceScreen
import com.lenix.ui.screens.SettingsScreen
import com.lenix.ui.screens.TerminalScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val INSTANCES = "instances"
    const val INSTALL = "install"
    const val TERMINAL = "terminal"
    const val DESKTOP = "desktop"
}

/**
 * App shell and navigation. Screens are kept in separate files on purpose; do not
 * grow main activity logic here.
 */
@Composable
fun LenixApp() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(application) }
    val homeUiState by homeViewModel.uiState.collectAsState()

    LaunchedEffect(homeUiState.navigateTo) {
        val dest = homeUiState.navigateTo ?: return@LaunchedEffect
        navController.navigate(dest)
        homeViewModel.consumeNavigation()
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                state = homeUiState,
                onInstall = homeViewModel::install,
                onCancelInstall = homeViewModel::cancelInstall,
                onStart = homeViewModel::start,
                onStop = homeViewModel::stop,
                onReset = homeViewModel::reset,
                onAutofixEngine = homeViewModel::autofixEngine,
                onOpenInstance = { navController.navigate(Routes.INSTANCES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                onOpenDesktop = { navController.navigate(Routes.DESKTOP) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = homeUiState.settings,
                onUpdate = homeViewModel::updateSettings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INSTANCES) {
            InstanceScreen(
                state = homeUiState,
                diskUsage = homeViewModel::diskUsageBytes,
                onSelect = homeViewModel::selectInstance,
                onCreate = homeViewModel::createInstance,
                onRename = homeViewModel::renameInstance,
                onDelete = homeViewModel::deleteInstance,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INSTALL) {
            InstallScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TERMINAL) {
            // Collected here, not inside the screen, so the window recomposes when a
            // session starts, exits or is stopped while the terminal is open.
            val terminal by homeViewModel.terminalState.collectAsState()
            TerminalScreen(
                snapshot = terminal,
                onSend = homeViewModel::sendToTerminal,
                onSendRaw = homeViewModel::sendRawToTerminal,
                onCtrlC = homeViewModel::sendCtrlCToTerminal,
                onCtrlD = homeViewModel::sendCtrlDToTerminal,
                onEndOfInput = homeViewModel::sendEofToTerminal,
                onClear = homeViewModel::clearTerminal,
                onBack = { navController.popBackStack() },
                onHome = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.DESKTOP) {
            DesktopScreen(
                guestRuntime = homeViewModel.guestRuntime,
                instanceId = homeUiState.selectedInstance.id,
                vncPort = homeViewModel.vncPort(),
                running = homeUiState.selectedInstance.state == com.lenix.vm.VmState.RUNNING,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
