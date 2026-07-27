package com.nekonf.nekostatus

import android.Manifest
import android.app.AppOpsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nekonf.nekostatus.core.designsystem.NekoTheme
import com.nekonf.nekostatus.feature.activity.ActivityScreen
import com.nekonf.nekostatus.feature.auth.AuthScreen
import com.nekonf.nekostatus.feature.auth.QrScannerScreen
import com.nekonf.nekostatus.feature.devices.DevicesScreen
import com.nekonf.nekostatus.feature.devices.PermissionKind
import com.nekonf.nekostatus.feature.devices.PermissionStatus
import com.nekonf.nekostatus.feature.overview.OverviewScreen
import com.nekonf.nekostatus.feature.settings.SettingsDestination
import com.nekonf.nekostatus.feature.settings.SettingsDetailScreen
import com.nekonf.nekostatus.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by appViewModel.uiState.collectAsState()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark =
                when (state.themeMode) {
                    "light" -> false
                    "dark" -> true
                    else -> systemDark
                }
            NekoTheme(darkTheme = dark, dynamicColor = state.dynamicColor) {
                NekoApp(state, appViewModel::completeOnboarding)
            }
        }
    }
}

private enum class MainDestination(val route: String, val label: Int, val icon: ImageVector) {
    OVERVIEW("overview", R.string.nav_overview, Icons.Rounded.Home),
    ACTIVITY("activity", R.string.nav_activity, Icons.Rounded.Timeline),
    DEVICES("devices", R.string.nav_devices, Icons.Rounded.Devices),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
private fun NekoApp(
    state: AppUiState,
    onCompleteOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var pendingPairToken by rememberSaveable { mutableStateOf<String?>(null) }
    var openScannerAfterPermission by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            showScanner = granted && openScannerAfterPermission
            openScannerAfterPermission = false
        }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val permissionStatuses = rememberPermissionStatuses()

    fun openScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            openScannerAfterPermission = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openPermission(kind: PermissionKind) {
        when (kind) {
            PermissionKind.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            PermissionKind.USAGE_ACCESS -> context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            PermissionKind.MEDIA_ACCESS -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            PermissionKind.ACCESSIBILITY -> context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    when {
        showScanner ->
            QrScannerScreen(
                onDetected = {
                    pendingPairToken = it
                    showScanner = false
                },
                onBack = { showScanner = false },
            )
        !state.canReport ->
            AuthScreen(
                onOpenScanner = ::openScanner,
                allowLocalServer = BuildConfig.ALLOW_CLEARTEXT,
                pairToken = pendingPairToken,
                onPairTokenConsumed = { pendingPairToken = null },
            )
        !state.onboardingComplete ->
            DevicesScreen(
                deviceName = state.deviceCredential?.deviceName ?: Build.MODEL,
                permissions = permissionStatuses,
                onPermissionClick = ::openPermission,
                onOpenWidgetSettings = { requestWidgetPin(context, snapshot = false) },
                onboarding = true,
                onCompleteOnboarding = onCompleteOnboarding,
            )
        else -> MainShell(state, permissionStatuses, ::openPermission)
    }
}

@Composable
private fun MainShell(
    state: AppUiState,
    permissions: List<PermissionStatus>,
    onOpenPermission: (PermissionKind) -> Unit,
) {
    val context = LocalContext.current
    val updateUiState by UpdateManager.uiState.collectAsState()
    val colors = MaterialTheme.colorScheme
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: MainDestination.OVERVIEW.route
    val mainRoutes = MainDestination.entries.map { it.route }.toSet()
    val navigateMain: (MainDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.OVERVIEW.route,
            modifier = modifier,
        ) {
            composable(MainDestination.OVERVIEW.route) {
                OverviewScreen(
                    onStartReporting = { ReportingService.start(context) },
                    onStopReporting = { ReportingService.stop(context) },
                    onOpenPermissions = { navigateMain(MainDestination.DEVICES) },
                )
            }
            composable(MainDestination.ACTIVITY.route) { ActivityScreen(state.capabilities) }
            composable(MainDestination.DEVICES.route) {
                DevicesScreen(
                    deviceName = state.deviceCredential?.deviceName ?: Build.MODEL,
                    permissions = permissions,
                    onPermissionClick = onOpenPermission,
                    onOpenWidgetSettings = { requestWidgetPin(context, snapshot = false) },
                )
            }
            composable(MainDestination.SETTINGS.route) {
                SettingsScreen(onOpen = { navController.navigate("settings/${it.name}") })
            }
            composable("settings/{destination}") { entry ->
                val destination =
                    runCatching {
                        SettingsDestination.valueOf(entry.arguments?.getString("destination").orEmpty())
                    }.getOrDefault(SettingsDestination.ABOUT)
                SettingsDetailScreen(
                    destination = destination,
                    onBack = navController::popBackStack,
                    appVersion = BuildConfig.VERSION_NAME,
                    allowLocalServer = BuildConfig.ALLOW_CLEARTEXT,
                    onOpenPermissions = { navigateMain(MainDestination.DEVICES) },
                    onOpenWidgets = { snapshot -> requestWidgetPin(context, snapshot) },
                    updateUiState = updateUiState,
                    onCheckForUpdates = { UpdateManager.checkNow(context) },
                    onDownloadUpdate = { UpdateManager.downloadAvailableUpdate(context) },
                    onInstallUpdate = { UpdateInstallActivity.launch(context) },
                    onReportingStopRequired = { ReportingService.stopImmediately(context) },
                    onWidgetScheduleChanged = { enabled, interval ->
                        WidgetRefreshScheduler.sync(context, enabled, interval)
                    },
                    onWidgetRefreshRequested = { WidgetRefreshScheduler.refreshNow(context) },
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 700.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                if (route in mainRoutes) {
                    NavigationRail {
                        MainDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = route == destination.route,
                                onClick = { navigateMain(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.label)) },
                                colors =
                                    NavigationRailItemDefaults.colors(
                                        selectedIconColor = colors.onPrimaryContainer,
                                        selectedTextColor = colors.onPrimaryContainer,
                                        indicatorColor = colors.primaryContainer,
                                        unselectedIconColor = colors.onSurfaceVariant,
                                        unselectedTextColor = colors.onSurfaceVariant,
                                    ),
                            )
                        }
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (route in mainRoutes) {
                        NavigationBar {
                            MainDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = route == destination.route,
                                    onClick = { navigateMain(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(stringResource(destination.label)) },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = colors.onPrimaryContainer,
                                            selectedTextColor = colors.onPrimaryContainer,
                                            indicatorColor = colors.primaryContainer,
                                            unselectedIconColor = colors.onSurfaceVariant,
                                            unselectedTextColor = colors.onSurfaceVariant,
                                        ),
                                )
                            }
                        }
                    }
                },
            ) { padding -> content(Modifier.padding(padding)) }
        }
    }
}

@Composable
private fun rememberPermissionStatuses(): List<PermissionStatus> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(refresh) {
        listOf(
            PermissionStatus(
                PermissionKind.NOTIFICATIONS,
                Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            ),
            PermissionStatus(PermissionKind.USAGE_ACCESS, hasUsageAccess(context)),
            PermissionStatus(
                PermissionKind.MEDIA_ACCESS,
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            ),
            PermissionStatus(PermissionKind.ACCESSIBILITY, isAccessibilityEnabled(context)),
        )
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
    val component = ComponentName(context, NekoAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun requestWidgetPin(
    context: Context,
    snapshot: Boolean,
) {
    val manager = AppWidgetManager.getInstance(context)
    val provider =
        ComponentName(
            context,
            if (snapshot) NekoSnapshotWidgetReceiver::class.java else NekoWidgetReceiver::class.java,
        )
    if (manager.isRequestPinAppWidgetSupported) {
        val accepted = manager.requestPinAppWidget(provider, null, null)
        Toast.makeText(
            context,
            if (accepted) R.string.widget_pin_requested else R.string.widget_pin_failed,
            Toast.LENGTH_SHORT,
        ).show()
    } else {
        Toast.makeText(context, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
    }
}
