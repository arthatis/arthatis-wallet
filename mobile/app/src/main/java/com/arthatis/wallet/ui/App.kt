package com.arthatis.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route

@Composable
fun ArthatisApp(vm: AppViewModel, pickBackup: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val stack by vm.stack.collectAsState()
    val tab by vm.tab.collectAsState()
    val snack by vm.snack.collectAsState()
    val host = remember { SnackbarHostState() }
    val route = stack.last()

    LaunchedEffect(snack) {
        val m = snack ?: return@LaunchedEffect
        val result = host.showSnackbar(m.text, m.action, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        vm.snackShown()
    }
    BackHandler(stack.size > 1 || (route == Route.Main && tab != Tab.Home)) {
        if (stack.size > 1) vm.back() else vm.selectTab(Tab.Home)
    }

    // System bar icons follow the app's own theme (not just the system's), for gesture and 3-button navigation alike.
    val activity = androidx.activity.compose.LocalActivity.current as? ComponentActivity
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    DisposableEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        )
        // No translucent scrim behind the 3-button bar: the app draws its own surface edge to edge.
        if (android.os.Build.VERSION.SDK_INT >= 29) activity?.window?.isNavigationBarContrastEnforced = false
        onDispose { }
    }

    ArthatisTheme(settings.theme, settings.lang) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(host) },
                bottomBar = { if (route == Route.Main) NavBar(tab, vm::selectTab) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).statusBarsPadding().then(if (route == Route.Main) Modifier else Modifier.navigationBarsPadding()).imePadding()) {
                    Crossfade(route to tab, label = "route") { (r, t) ->
                        when (r) {
                            Route.Loading -> LoadingScreen(vm)
                            Route.Welcome -> WelcomeScreen(vm)
                            Route.Restore -> RestoreScreen(vm, pickBackup)
                            Route.Onboarding -> OnboardingScreen(vm)
                            Route.Main -> when (t) {
                                Tab.Home -> HomeScreen(vm)
                                Tab.Wallets -> WalletsScreen(vm)
                                Tab.Transactions -> TransactionsScreen(vm)
                                Tab.Bareng -> BarengScreen(vm)
                                Tab.Settings -> SettingsScreen(vm)
                            }
                            is Route.WalletDetail -> WalletDetailScreen(vm, r.id)
                            is Route.AddTx -> AddTxScreen(vm, r)
                            Route.Receipt -> ReceiptScreen(vm)
                            Route.AddWallet -> AddWalletScreen(vm)
                            is Route.Transfer -> TransferScreen(vm, r)
                            Route.Chat -> ChatScreen(vm)
                            is Route.BarengDetail -> BarengDetailScreen(vm, r.id)
                            Route.BarengCreate -> BarengCreateScreen(vm)
                            is Route.Invite -> InviteScreen(vm, r.id)
                            Route.BarengJoin -> BarengJoinScreen(vm)
                            Route.Security -> SecurityScreen(vm)
                            Route.DataBackup -> DataBackupScreen(vm, pickBackup)
                            Route.ImportPreview -> ImportPreviewScreen(vm)
                            Route.ServerSettings -> ServerSettingsScreen(vm)
                            Route.Recurring -> RecurringScreen(vm)
                            is Route.EditWallet -> AddWalletScreen(vm, r.id)
                            is Route.RecurringForm -> RecurringFormScreen(vm, r.id)
                            Route.Categories -> CategoriesScreen(vm)
                            Route.AiSettings -> AiSettingsScreen(vm)
                            Route.Budgets -> BudgetsScreen(vm)
                            Route.LinkWeb -> LinkWebScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
