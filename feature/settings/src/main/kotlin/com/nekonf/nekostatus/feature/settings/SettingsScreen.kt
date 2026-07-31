@file:Suppress("MatchingDeclarationName")

package com.nekonf.nekostatus.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.nekonf.nekostatus.core.designsystem.NekoPanel
import com.nekonf.nekostatus.core.designsystem.PrimaryAction
import com.nekonf.nekostatus.core.designsystem.SectionHeader
import com.nekonf.nekostatus.core.designsystem.SettingsRow
import com.nekonf.nekostatus.core.model.ReportingSettings
import com.nekonf.nekostatus.core.model.ServerConfig
import com.nekonf.nekostatus.core.model.UpdateFailureStage
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateSource
import com.nekonf.nekostatus.core.model.UpdateStatus
import com.nekonf.nekostatus.core.model.UpdateUiState
import com.nekonf.nekostatus.core.model.WidgetAvailability
import com.nekonf.nekostatus.core.model.WidgetDeviceStatus
import com.nekonf.nekostatus.core.model.WidgetDisplayMode
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.WidgetTheme
import com.nekonf.nekostatus.core.model.WidgetUserStatus
import com.nekonf.nekostatus.core.model.normalizeGitHubRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class SettingsDestination {
    ACCOUNT,
    SERVER,
    REPORTING,
    PERMISSIONS,
    WIDGETS,
    APPEARANCE,
    PRIVACY,
    UPDATES,
    DIAGNOSTICS,
    ABOUT,
}

internal object UpdatePageTestTags {
    const val ROOT = "update_page"
    const val AUTO_CHECK = "update_auto_check"
    const val AUTO_DOWNLOAD = "update_auto_download"
    const val SOURCE_SELECTOR = "update_source_selector"
    const val SOURCE_OFFICIAL = "update_source_official"
    const val SOURCE_CUSTOM = "update_source_custom"
    const val REPOSITORY_INPUT = "update_repository_input"
    const val REPOSITORY_SAVE = "update_repository_save"
    const val NOTIFICATION_SETTINGS = "update_notification_settings"
    const val STATUS = "update_status"
    const val MAIN_ACTION = "update_main_action"
    const val CHECK_AGAIN = "update_check_again"
    const val VIEW_RELEASE = "update_view_release"
}

private const val UPDATE_NOTIFICATION_CHANNEL = "neko-status-updates"

@Composable
fun SettingsScreen(
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(14.dp))
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                SettingsRow(
                    title = state.user?.username ?: stringResource(R.string.settings_account),
                    supporting = state.user?.email ?: stringResource(R.string.settings_account_support),
                    icon = Icons.Rounded.AccountCircle,
                    onClick = { onOpen(SettingsDestination.ACCOUNT) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_server),
                    supporting = state.serverConfig.activeUrl,
                    icon = Icons.Rounded.Public,
                    onClick = { onOpen(SettingsDestination.SERVER) },
                )
            }
            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.settings_device_section))
            Spacer(Modifier.height(8.dp))
            NekoPanel(Modifier.fillMaxWidth()) {
                SettingsRow(
                    title = stringResource(R.string.settings_reporting),
                    supporting = stringResource(R.string.settings_reporting_support),
                    icon = Icons.Rounded.Sync,
                    onClick = { onOpen(SettingsDestination.REPORTING) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_permissions),
                    icon = Icons.Rounded.Notifications,
                    onClick = { onOpen(SettingsDestination.PERMISSIONS) },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_widgets),
                    icon = Icons.Rounded.Widgets,
                    onClick = { onOpen(SettingsDestination.WIDGETS) },
                )
            }
            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.settings_app_section))
            Spacer(Modifier.height(8.dp))
            NekoPanel(Modifier.fillMaxWidth()) {
                listOf(
                    Triple(SettingsDestination.APPEARANCE, R.string.settings_appearance, Icons.Rounded.ColorLens),
                    Triple(SettingsDestination.PRIVACY, R.string.settings_privacy, Icons.Rounded.PrivacyTip),
                    Triple(SettingsDestination.UPDATES, R.string.settings_updates, Icons.Rounded.SystemUpdate),
                    Triple(SettingsDestination.DIAGNOSTICS, R.string.settings_diagnostics, Icons.Rounded.BugReport),
                    Triple(SettingsDestination.ABOUT, R.string.settings_about, Icons.Rounded.Info),
                ).forEach { (destination, label, icon) ->
                    SettingsRow(stringResource(label), icon = icon, onClick = { onOpen(destination) })
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun SettingsDetailScreen(
    destination: SettingsDestination,
    onBack: () -> Unit,
    appVersion: String,
    allowLocalServer: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenWidgets: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onReportingStopRequired: () -> Unit,
    onWidgetScheduleChanged: (Boolean, Int) -> Unit,
    onWidgetRefreshRequested: () -> Unit,
    updateUiState: UpdateUiState = UpdateUiState(),
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(destination.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        when (destination) {
            SettingsDestination.ACCOUNT ->
                AccountPage(
                    state = state,
                    onSave = viewModel::saveProfile,
                    onChangePassword = viewModel::changePassword,
                    onLogout = {
                        onReportingStopRequired()
                        viewModel.logout {
                            onWidgetScheduleChanged(false, state.widgetSettings.refreshIntervalMinutes)
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            SettingsDestination.SERVER ->
                ServerPage(
                    config = state.serverConfig,
                    allowLocal = allowLocalServer,
                    onSave = {
                        if (it.activeUrl != state.serverConfig.activeUrl) onReportingStopRequired()
                        viewModel.saveServer(it)
                    },
                    modifier = Modifier.padding(padding),
                )
            SettingsDestination.REPORTING ->
                ReportingPage(state.reportingSettings, viewModel::updateReportingSettings, Modifier.padding(padding))
            SettingsDestination.PERMISSIONS ->
                RedirectPage(
                    R.string.settings_permissions_support,
                    R.string.settings_open_permissions,
                    onOpenPermissions,
                    Modifier.padding(padding),
                )
            SettingsDestination.WIDGETS ->
                WidgetSettingsPage(
                    state = state,
                    onSettingsChange = {
                        viewModel.setWidgetSettings(it)
                        onWidgetScheduleChanged(it.enabled, it.refreshIntervalMinutes)
                    },
                    onEnabledChange = { enabled ->
                        viewModel.setWidgetEnabled(enabled) { ready ->
                            onWidgetScheduleChanged(ready, state.widgetSettings.refreshIntervalMinutes)
                            if (ready) onWidgetRefreshRequested()
                        }
                    },
                    onRefresh = onWidgetRefreshRequested,
                    onAdd = onOpenWidgets,
                    modifier = Modifier.padding(padding),
                )
            SettingsDestination.APPEARANCE -> AppearancePage(state, viewModel::setTheme, Modifier.padding(padding))
            SettingsDestination.PRIVACY -> PrivacyPage(Modifier.padding(padding))
            SettingsDestination.UPDATES ->
                UpdatePage(
                    appVersion = appVersion,
                    settings = state.updateSettings,
                    updateState = updateUiState,
                    onAutomaticChecksChange = { viewModel.setAutomaticUpdates(automaticChecks = it) },
                    onAutomaticDownloadChange = { viewModel.setAutomaticUpdates(automaticDownload = it) },
                    onSelectSource = { viewModel.setUpdateRepository(it) },
                    onSaveCustomRepository = { repository, onSaved ->
                        viewModel.setUpdateRepository(UpdateSource.CUSTOM, repository, onSaved)
                    },
                    onCheck = onCheckForUpdates,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    modifier = Modifier.padding(padding),
                )
            SettingsDestination.DIAGNOSTICS -> DiagnosticsPage(viewModel, Modifier.padding(padding))
            SettingsDestination.ABOUT -> AboutPage(appVersion, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AccountPage(
    state: SettingsUiState,
    onSave: (String, String, String?) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier,
) {
    val user = state.user
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember(user?.username) { mutableStateOf(user?.username.orEmpty()) }
    var email by remember(user?.email) { mutableStateOf(user?.email.orEmpty()) }
    var avatarPayload by remember(user?.id, user?.avatarUrl) { mutableStateOf<String?>(null) }
    var avatarPreview by remember(user?.id, user?.avatarUrl) { mutableStateOf<Any?>(user?.avatarUrl) }
    var avatarProcessing by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf(false) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }
    val emailValid = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordTooShort = newPassword.isNotEmpty() && newPassword.length < 6
    val passwordMismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val passwordValid = isValidPasswordChange(currentPassword, newPassword, confirmPassword)
    val avatarPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    avatarProcessing = true
                    avatarError = false
                    encodeAvatarDataUri(context, uri)
                        .onSuccess {
                            avatarPayload = it
                            avatarPreview = uri
                        }.onFailure {
                            avatarError = true
                        }
                    avatarProcessing = false
                }
            }
        }

    LaunchedEffect(state.passwordSaved) {
        if (state.passwordSaved) {
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (avatarPreview != null) {
                            AsyncImage(
                                model = avatarPreview,
                                contentDescription = stringResource(R.string.profile_avatar),
                                modifier = Modifier.size(72.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(72.dp),
                            )
                        }
                        TextButton(
                            onClick = {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = !avatarProcessing && !state.profileSaving,
                        ) {
                            if (avatarProcessing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.profile_change_avatar))
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user?.username ?: "--", style = MaterialTheme.typography.titleLarge)
                        Text(
                            user?.email ?: stringResource(R.string.settings_no_email),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        user?.id?.let {
                            Text(
                                stringResource(R.string.profile_user_id, it),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (avatarError) {
                    Text(
                        stringResource(R.string.profile_avatar_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_username)) },
                    isError = username.isBlank(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_email)) },
                    supportingText =
                        if (!emailValid) {
                            { Text(stringResource(R.string.profile_email_invalid)) }
                        } else {
                            null
                        },
                    isError = !emailValid,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryAction(
                    text =
                        stringResource(
                            if (state.profileSaving) {
                                R.string.profile_saving
                            } else {
                                R.string.profile_save
                            },
                        ),
                    onClick = { onSave(username.trim(), email.trim(), avatarPayload) },
                    enabled = username.isNotBlank() && emailValid && !avatarProcessing && !state.profileSaving,
                )
                state.profileError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (state.profileSaved) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.profile_saved), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.profile_password_title))
            NekoPanel(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.profile_current_password)) },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.profile_new_password)) },
                    supportingText =
                        if (passwordTooShort) {
                            { Text(stringResource(R.string.profile_password_too_short)) }
                        } else {
                            null
                        },
                    isError = passwordTooShort,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.profile_confirm_password)) },
                    supportingText =
                        if (passwordMismatch) {
                            { Text(stringResource(R.string.profile_password_mismatch)) }
                        } else {
                            null
                        },
                    isError = passwordMismatch,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryAction(
                    text =
                        stringResource(
                            if (state.passwordSaving) {
                                R.string.profile_password_saving
                            } else {
                                R.string.profile_password_save
                            },
                        ),
                    onClick = { onChangePassword(currentPassword, newPassword) },
                    enabled = passwordValid && !state.passwordSaving,
                )
                state.passwordError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (state.passwordSaved) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.profile_password_saved), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            OutlinedButton(onClick = { showLogoutConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        onLogout()
                    },
                ) {
                    Text(stringResource(R.string.logout_confirm_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text(stringResource(R.string.logout_cancel))
                }
            },
        )
    }
}

internal fun isValidPasswordChange(
    currentPassword: String,
    newPassword: String,
    confirmation: String,
): Boolean =
    currentPassword.isNotEmpty() &&
        newPassword.length >= 6 &&
        confirmation == newPassword

@Composable
private fun ServerPage(
    config: ServerConfig,
    allowLocal: Boolean,
    onSave: (ServerConfig) -> Unit,
    modifier: Modifier,
) {
    var productionUrl by remember(config.productionUrl) { mutableStateOf(config.productionUrl) }
    var localUrl by remember(config.localUrl) { mutableStateOf(config.localUrl) }
    var useLocal by remember(config.useLocalServer) { mutableStateOf(config.useLocalServer && allowLocal) }
    val productionValid = productionUrl.startsWith("https://")
    val localValid = !useLocal || (allowLocal && (localUrl.startsWith("http://") || localUrl.startsWith("https://")))
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = productionUrl,
            onValueChange = { productionUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_production_url)) },
            isError = !productionValid,
        )
        if (allowLocal) {
            NekoPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_local_server), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_debug_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = useLocal, onCheckedChange = { useLocal = it })
                }
                if (useLocal) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = localUrl,
                        onValueChange = { localUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_local_url)) },
                        isError = !localValid,
                    )
                }
            }
        }
        PrimaryAction(
            text = stringResource(R.string.settings_save),
            onClick = {
                onSave(ServerConfig(productionUrl.trimEnd('/'), localUrl.trimEnd('/'), useLocal && allowLocal))
            },
            enabled = productionValid && localValid,
        )
    }
}

@Composable
private fun ReportingPage(
    settings: ReportingSettings,
    onUpdate: (ReportingSettings) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var interval by remember(settings.intervalSeconds) { mutableFloatStateOf(settings.intervalSeconds.toFloat()) }
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_report_interval), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_report_interval_value, interval.roundToInt()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = interval,
                    onValueChange = { interval = it },
                    valueRange = 10f..300f,
                    steps = 28,
                    onValueChangeFinished = {
                        val rounded = (interval / 10f).roundToInt().coerceIn(1, 30) * 10
                        interval = rounded.toFloat()
                        onUpdate(settings.copy(intervalSeconds = rounded))
                    },
                )
            }
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_restore_after_boot),
                    supporting = stringResource(R.string.settings_restore_after_boot_support),
                    checked = settings.restoreAfterBoot,
                    onCheckedChange = { onUpdate(settings.copy(restoreAfterBoot = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_media_status),
                    supporting = stringResource(R.string.settings_media_status_support),
                    checked = settings.includeMedia,
                    onCheckedChange = { onUpdate(settings.copy(includeMedia = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_enhanced_detection),
                    supporting = stringResource(R.string.settings_enhanced_detection_support),
                    checked = settings.enhancedAppDetection,
                    onCheckedChange = { onUpdate(settings.copy(enhancedAppDetection = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_keep_alive_reminder),
                    supporting = stringResource(R.string.settings_keep_alive_reminder_support),
                    checked = settings.keepAliveReminderEnabled,
                    onCheckedChange = { onUpdate(settings.copy(keepAliveReminderEnabled = it)) },
                )
            }
        }
        if (settings.keepAliveReminderEnabled) {
            item {
                SectionHeader(stringResource(R.string.settings_keep_alive_interval))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        6 to R.string.settings_keep_alive_6h,
                        12 to R.string.settings_keep_alive_12h,
                        24 to R.string.settings_keep_alive_24h,
                        48 to R.string.settings_keep_alive_48h,
                    ).forEachIndexed { index, (hours, label) ->
                        SegmentedButton(
                            selected = settings.keepAliveReminderIntervalHours == hours,
                            onClick = { onUpdate(settings.copy(keepAliveReminderIntervalHours = hours)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4),
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
            if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                item {
                    NekoPanel(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.settings_keep_alive_notifications_disabled),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.settings_open_notification_settings))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun WidgetSettingsPage(
    state: SettingsUiState,
    onSettingsChange: (WidgetSettings) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAdd: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val settings = state.widgetSettings
    val feedState = state.widgetFeedState
    val isAdmin = state.widgetUserType == "admin"
    val widgetPalette = widgetPreviewPalette(settings.theme)
    var refreshInterval by
        remember(settings.refreshIntervalMinutes) { mutableFloatStateOf(settings.refreshIntervalMinutes.toFloat()) }
    var opacity by
        remember(settings.backgroundOpacityPercent) { mutableFloatStateOf(settings.backgroundOpacityPercent.toFloat()) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(4.dp))
            WidgetPreview(state, onRefresh)
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.widget_settings_enabled),
                    supporting =
                        state.widgetUsername?.let { stringResource(R.string.widget_settings_bound_as, it) }
                            ?: stringResource(R.string.widget_settings_enable_support),
                    checked = settings.enabled,
                    enabled = feedState.availability != WidgetAvailability.LOADING,
                    onCheckedChange = onEnabledChange,
                )
                if (feedState.availability == WidgetAvailability.LOADING) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                feedState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.widget_settings_scope))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    WidgetDisplayMode.ALL to R.string.widget_settings_all_users,
                    WidgetDisplayMode.SINGLE to R.string.widget_settings_single_user,
                ).forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.displayMode == mode,
                        onClick = {
                            val selectedUser =
                                feedState.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                                    ?: feedState.feed?.users?.firstOrNull()
                            onSettingsChange(
                                if (mode == WidgetDisplayMode.SINGLE) {
                                    settings.copy(
                                        displayMode = mode,
                                        targetUserId = selectedUser?.userId,
                                        selectedDeviceIds =
                                            settings.selectedDeviceIds.ifEmpty {
                                                selectedUser?.devices.orEmpty().take(2).map { it.deviceId }
                                            },
                                    )
                                } else {
                                    settings.copy(displayMode = mode)
                                },
                            )
                        },
                        enabled = settings.enabled && (mode != WidgetDisplayMode.ALL || isAdmin),
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
            }
            if (!isAdmin && settings.enabled) {
                Text(
                    stringResource(R.string.widget_settings_scope_managed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (settings.enabled && isAdmin && settings.displayMode == WidgetDisplayMode.SINGLE) {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    val users = feedState.feed?.users.orEmpty()
                    if (users.isEmpty()) {
                        Text(stringResource(R.string.widget_no_visible_users))
                    } else {
                        users.forEach { user ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = settings.targetUserId == user.userId,
                                    onClick = {
                                        onSettingsChange(
                                            settings.copy(
                                                targetUserId = user.userId,
                                                selectedDeviceIds = user.devices.take(2).map { it.deviceId },
                                                targetDeviceId =
                                                    user.devices.firstOrNull {
                                                        !it.screenshotThumbnailUrl.isNullOrBlank() ||
                                                            !it.screenshotUrl.isNullOrBlank()
                                                    }?.deviceId
                                                        ?: user.devices.firstOrNull()?.deviceId,
                                            ),
                                        )
                                    },
                                )
                                Text(user.username, modifier = Modifier.weight(1f))
                                Text(
                                    stringResource(user.statusLabel()),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (settings.enabled && settings.displayMode == WidgetDisplayMode.SINGLE) {
            item {
                SectionHeader(stringResource(R.string.widget_settings_status_devices))
                NekoPanel(Modifier.fillMaxWidth()) {
                    val selectedUser =
                        feedState.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                            ?: feedState.feed?.users?.firstOrNull()
                    val devices = selectedUser?.devices.orEmpty()
                    Text(
                        stringResource(R.string.widget_settings_status_devices_support),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.widget_no_devices))
                    } else {
                        devices.forEach { device ->
                            val selected = device.deviceId in settings.selectedDeviceIds
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected,
                                    enabled = selected || settings.selectedDeviceIds.size < 2,
                                    onCheckedChange = { checked ->
                                        val next =
                                            when {
                                                checked ->
                                                    (settings.selectedDeviceIds + device.deviceId)
                                                        .distinct()
                                                        .take(2)
                                                settings.selectedDeviceIds.size > 1 ->
                                                    settings.selectedDeviceIds - device.deviceId
                                                else -> settings.selectedDeviceIds
                                            }
                                        onSettingsChange(settings.copy(selectedDeviceIds = next))
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(device.deviceName)
                                    Text(
                                        device.appName.takeIf(String::isNotBlank)
                                            ?: stringResource(R.string.widget_no_activity),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                WidgetBatteryIndicator(
                                    batteryLevel = device.batteryLevel,
                                    isCharging = device.isCharging,
                                    palette = widgetPalette,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.widget_settings_refresh))
            NekoPanel(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.widget_settings_refresh_value, refreshInterval.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = refreshInterval,
                    onValueChange = { refreshInterval = it },
                    enabled = settings.enabled,
                    valueRange = 15f..180f,
                    steps = 10,
                    onValueChangeFinished = {
                        val rounded = (refreshInterval / 15f).roundToInt().coerceIn(1, 12) * 15
                        refreshInterval = rounded.toFloat()
                        onSettingsChange(settings.copy(refreshIntervalMinutes = rounded))
                    },
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.widget_settings_appearance))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    WidgetTheme.SYSTEM to R.string.settings_theme_system,
                    WidgetTheme.LIGHT to R.string.settings_theme_light,
                    WidgetTheme.DARK to R.string.settings_theme_dark,
                ).forEachIndexed { index, (theme, label) ->
                    SegmentedButton(
                        selected = settings.theme == theme,
                        onClick = { onSettingsChange(settings.copy(theme = theme)) },
                        enabled = settings.enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) { Text(stringResource(label)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            NekoPanel(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.widget_settings_opacity, opacity.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    enabled = settings.enabled,
                    valueRange = 50f..100f,
                    steps = 4,
                    onValueChangeFinished = {
                        val rounded = (opacity / 10f).roundToInt().coerceIn(5, 10) * 10
                        opacity = rounded.toFloat()
                        onSettingsChange(settings.copy(backgroundOpacityPercent = rounded))
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.widget_settings_show_music),
                    checked = settings.showMusic,
                    enabled = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(showMusic = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.widget_settings_show_icons),
                    checked = settings.showIcons,
                    enabled = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(showIcons = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.widget_settings_show_device_switcher),
                    supporting = stringResource(R.string.widget_settings_show_device_switcher_support),
                    checked = settings.showDeviceSwitcher,
                    enabled = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(showDeviceSwitcher = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.widget_settings_show_screenshot),
                    supporting = stringResource(R.string.widget_settings_show_screenshot_support),
                    checked = settings.showScreenshot,
                    enabled = settings.enabled,
                    onCheckedChange = { enabled ->
                        val selectedUser =
                            feedState.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                                ?: feedState.feed?.users?.firstOrNull()
                        val selectedDevice =
                            selectedUser?.devices?.firstOrNull { it.deviceId == settings.targetDeviceId }
                                ?: selectedUser?.devices?.firstOrNull {
                                    !it.screenshotThumbnailUrl.isNullOrBlank() ||
                                        !it.screenshotUrl.isNullOrBlank()
                                }
                                ?: selectedUser?.devices?.firstOrNull()
                        onSettingsChange(
                            settings.copy(
                                showScreenshot = enabled,
                                displayMode = if (enabled) WidgetDisplayMode.SINGLE else settings.displayMode,
                                targetUserId = if (enabled) selectedUser?.userId else settings.targetUserId,
                                targetDeviceId = if (enabled) selectedDevice?.deviceId else settings.targetDeviceId,
                            ),
                        )
                    },
                )
            }
        }
        if (settings.enabled && settings.showScreenshot) {
            item {
                SectionHeader(stringResource(R.string.widget_settings_device))
                NekoPanel(Modifier.fillMaxWidth()) {
                    val selectedUser =
                        feedState.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                            ?: feedState.feed?.users?.firstOrNull()
                    val devices = selectedUser?.devices.orEmpty()
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.widget_no_devices))
                    } else {
                        devices.forEach { device ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = settings.targetDeviceId == device.deviceId,
                                    onClick = { onSettingsChange(settings.copy(targetDeviceId = device.deviceId)) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(device.deviceName)
                                    Text(
                                        device.appName.takeIf(String::isNotBlank)
                                            ?: stringResource(R.string.widget_no_activity),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                WidgetBatteryIndicator(
                                    batteryLevel = device.batteryLevel,
                                    isCharging = device.isCharging,
                                    palette = widgetPalette,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onRefresh,
                enabled = settings.enabled && feedState.availability != WidgetAvailability.LOADING,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (feedState.availability == WidgetAvailability.LOADING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (feedState.availability == WidgetAvailability.LOADING) {
                            R.string.widget_settings_refreshing
                        } else {
                            R.string.widget_settings_refresh_now
                        },
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            PrimaryAction(
                text =
                    stringResource(
                        if (settings.showScreenshot) {
                            R.string.widget_settings_add_snapshot
                        } else {
                            R.string.widget_settings_add_status
                        },
                    ),
                onClick = { onAdd(settings.showScreenshot) },
                enabled = settings.enabled,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class WidgetPreviewPalette(
    val desktop: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val accent: Color,
    val accentContainer: Color,
    val away: Color,
    val batteryLow: Color,
    val divider: Color,
)

@Composable
private fun WidgetPreview(
    state: SettingsUiState,
    onRefresh: () -> Unit,
) {
    val settings = state.widgetSettings
    val feedState = state.widgetFeedState
    val palette = widgetPreviewPalette(settings.theme)
    if (settings.showScreenshot) {
        WidgetSnapshotPreview(state, onRefresh, palette)
        return
    }
    val opacity = settings.backgroundOpacityPercent.coerceIn(50, 100) / 100f
    val feed = feedState.feed
    val selectedUser =
        feed?.users?.firstOrNull { it.userId == settings.targetUserId }
            ?: feed?.users?.firstOrNull()
    val devicePages = previewDevicePages(selectedUser?.devices.orEmpty(), settings.selectedDeviceIds)
    val switcherPreview =
        widgetSwitcherPreviewState(
            requested = settings.showDeviceSwitcher,
            applicable = settings.displayMode == WidgetDisplayMode.SINGLE,
            pageCount = devicePages.size,
        )
    var previewPage by remember(devicePages) { mutableIntStateOf(0) }
    val title =
        if (settings.displayMode == WidgetDisplayMode.SINGLE) {
            selectedUser?.username ?: stringResource(R.string.widget_settings_preview_title)
        } else {
            stringResource(R.string.widget_settings_preview_title)
        }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.widget_settings_preview))
        Spacer(Modifier.height(8.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.42f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.desktop)
                    .padding(horizontal = 12.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.08f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surface.copy(alpha = opacity))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = palette.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (switcherPreview.visible) {
                        IconButton(
                            onClick = { previewPage = (previewPage + 1).mod(devicePages.size) },
                            enabled = switcherPreview.enabled,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Sync,
                                contentDescription = stringResource(R.string.widget_settings_switch_device),
                                tint = if (switcherPreview.enabled) palette.accent else palette.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Text(
                        text = widgetPreviewUpdatedLabel(feedState),
                        color = palette.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = settings.enabled && feedState.availability != WidgetAvailability.LOADING,
                        modifier = Modifier.size(32.dp),
                    ) {
                        if (feedState.availability == WidgetAvailability.LOADING) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = palette.accent, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.widget_settings_refresh_now),
                                tint = if (settings.enabled) palette.accent else palette.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
                when {
                    !settings.enabled ->
                        WidgetPreviewMessage(
                            R.string.widget_settings_preview_disabled,
                            palette,
                            showProgress = false,
                        )
                    feed == null ->
                        WidgetPreviewMessage(
                            feedState.availability.widgetStateMessage(),
                            palette,
                            showProgress = feedState.availability == WidgetAvailability.LOADING,
                        )
                    feed.users.isEmpty() ->
                        WidgetPreviewMessage(R.string.widget_no_visible_users, palette, showProgress = false)
                    settings.displayMode == WidgetDisplayMode.SINGLE && selectedUser != null ->
                        WidgetPreviewDevices(devicePages.getOrNull(previewPage).orEmpty(), settings, palette)
                    else -> WidgetPreviewUsers(feed.users.take(2), settings, palette)
                }
            }
        }
        if (switcherPreview.visible && !switcherPreview.enabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (settings.displayMode == WidgetDisplayMode.SINGLE) {
                        R.string.widget_settings_switcher_no_more_devices
                    } else {
                        R.string.widget_settings_switcher_single_user_required
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WidgetPreviewMessage(
    message: Int,
    palette: WidgetPreviewPalette,
    showProgress: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = palette.accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            stringResource(message),
            color = palette.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WidgetSnapshotPreview(
    state: SettingsUiState,
    onRefresh: () -> Unit,
    palette: WidgetPreviewPalette,
) {
    val settings = state.widgetSettings
    val feedState = state.widgetFeedState
    val user =
        feedState.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
            ?: feedState.feed?.users?.firstOrNull()
    val screenshotDevices =
        user?.devices.orEmpty()
            .filter { !it.screenshotThumbnailUrl.isNullOrBlank() || !it.screenshotUrl.isNullOrBlank() }
            .let { devices ->
                val selected = devices.firstOrNull { it.deviceId == settings.targetDeviceId }
                listOfNotNull(selected) + devices.filterNot { it.deviceId == selected?.deviceId }
            }
    var previewPage by remember(screenshotDevices) { mutableIntStateOf(0) }
    val device =
        screenshotDevices.getOrNull(previewPage)
            ?: user?.devices?.firstOrNull { it.deviceId == settings.targetDeviceId }
            ?: user?.devices?.firstOrNull()
    val hasScreenshot = !device?.screenshotThumbnailUrl.isNullOrBlank() || !device?.screenshotUrl.isNullOrBlank()
    val switcherPreview =
        widgetSwitcherPreviewState(
            requested = settings.showDeviceSwitcher,
            applicable = true,
            pageCount = screenshotDevices.size,
        )
    val unavailableMessage =
        when {
            !settings.enabled -> R.string.widget_settings_preview_disabled
            feedState.feed == null -> feedState.availability.widgetStateMessage()
            user == null -> R.string.widget_no_visible_users
            device == null -> R.string.widget_no_devices
            !hasScreenshot -> R.string.widget_settings_preview_no_screenshot
            else -> R.string.widget_settings_preview_snapshot_available
        }
    val opacity = settings.backgroundOpacityPercent.coerceIn(50, 100) / 100f

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.widget_settings_preview_snapshot))
        Spacer(Modifier.height(8.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.02f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.desktop)
                    .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surface.copy(alpha = opacity))
                        .padding(11.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        user?.username ?: stringResource(R.string.widget_settings_preview_title),
                        modifier = Modifier.weight(1f),
                        color = palette.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (switcherPreview.visible) {
                        IconButton(
                            onClick = { previewPage = (previewPage + 1).mod(screenshotDevices.size) },
                            enabled = switcherPreview.enabled,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Sync,
                                contentDescription = stringResource(R.string.widget_settings_switch_device),
                                tint = if (switcherPreview.enabled) palette.accent else palette.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Text(
                        widgetPreviewUpdatedLabel(feedState),
                        color = palette.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = settings.enabled && feedState.availability != WidgetAvailability.LOADING,
                        modifier = Modifier.size(32.dp),
                    ) {
                        if (feedState.availability == WidgetAvailability.LOADING) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = palette.accent, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.widget_settings_refresh_now),
                                tint = palette.accent,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D0E10)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = null,
                            tint = palette.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(unavailableMessage),
                            color = palette.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (
                        hasScreenshot &&
                        (
                            feedState.availability == WidgetAvailability.OFFLINE ||
                                feedState.availability == WidgetAvailability.ERROR
                        )
                    ) {
                        Text(
                            stringResource(R.string.widget_settings_preview_cached_badge),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.away)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (device != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (settings.showIcons) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.accentContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Devices,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.deviceName,
                                color = palette.onSurface,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                device.appName.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.widget_no_activity),
                                color = palette.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        WidgetPreviewTelemetry(
                            status = stringResource(device.previewStatusLabel()),
                            batteryLevel = device.batteryLevel,
                            isCharging = device.isCharging,
                            statusColor = device.previewStatusColor(palette),
                            palette = palette,
                        )
                    }
                }
            }
        }
        if (switcherPreview.visible && !switcherPreview.enabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.widget_settings_switcher_no_more_devices),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal data class WidgetSwitcherPreviewState(
    val visible: Boolean,
    val enabled: Boolean,
)

internal fun widgetSwitcherPreviewState(
    requested: Boolean,
    applicable: Boolean,
    pageCount: Int,
): WidgetSwitcherPreviewState {
    val visible = requested
    return WidgetSwitcherPreviewState(
        visible = visible,
        enabled = visible && applicable && pageCount > 1,
    )
}

@Composable
private fun WidgetPreviewUsers(
    users: List<WidgetUserStatus>,
    settings: WidgetSettings,
    palette: WidgetPreviewPalette,
) {
    Column(Modifier.fillMaxSize()) {
        users.forEachIndexed { index, user ->
            WidgetPreviewUserRow(user, settings, palette, Modifier.weight(1f))
            if (index < users.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
            }
        }
    }
}

@Composable
private fun WidgetPreviewDevices(
    devices: List<WidgetDeviceStatus>,
    settings: WidgetSettings,
    palette: WidgetPreviewPalette,
) {
    if (devices.isEmpty()) {
        WidgetPreviewMessage(R.string.widget_no_devices, palette, showProgress = false)
        return
    }
    Column(Modifier.fillMaxSize()) {
        devices.forEachIndexed { index, device ->
            WidgetPreviewDeviceRow(device, settings, palette, Modifier.weight(1f))
            if (index < devices.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
            }
        }
    }
}

private fun previewDevicePages(
    devices: List<WidgetDeviceStatus>,
    selectedDeviceIds: List<String>,
): List<List<WidgetDeviceStatus>> {
    if (devices.isEmpty()) return emptyList()
    val requestedIds = selectedDeviceIds.filter(String::isNotBlank).distinct().take(2)
    val pageSize = requestedIds.size.takeIf { it > 0 } ?: minOf(2, devices.size)
    val selected = requestedIds.mapNotNull { id -> devices.firstOrNull { it.deviceId == id } }
    val firstPage = selected.ifEmpty { devices.take(pageSize) }
    val remaining = devices.filterNot { device -> firstPage.any { it.deviceId == device.deviceId } }
    return listOf(firstPage) + remaining.chunked(pageSize)
}

@Composable
private fun WidgetPreviewUserRow(
    user: WidgetUserStatus,
    settings: WidgetSettings,
    palette: WidgetPreviewPalette,
    modifier: Modifier,
) {
    val device = user.devices.firstOrNull()
    WidgetPreviewRow(
        modifier = modifier,
        title = user.username,
        subtitle =
            device?.let {
                stringResource(
                    R.string.widget_settings_preview_device_app,
                    it.deviceName,
                    it.appName.takeIf(String::isNotBlank) ?: stringResource(R.string.widget_no_activity),
                )
            } ?: stringResource(R.string.widget_no_activity),
        mediaTitle = device?.media?.title,
        status = stringResource(user.statusLabel()),
        batteryLevel = device?.batteryLevel,
        isCharging = device?.isCharging == true,
        statusColor = user.previewStatusColor(palette),
        showIcons = settings.showIcons,
        showMusic = settings.showMusic,
        iconText = user.username.trim().take(1).uppercase(),
        deviceIcon = false,
        palette = palette,
    )
}

@Composable
private fun WidgetPreviewDeviceRow(
    device: WidgetDeviceStatus,
    settings: WidgetSettings,
    palette: WidgetPreviewPalette,
    modifier: Modifier,
) {
    WidgetPreviewRow(
        modifier = modifier,
        title = device.deviceName,
        subtitle = device.appName.takeIf(String::isNotBlank) ?: stringResource(R.string.widget_no_activity),
        mediaTitle = device.media?.title,
        status = stringResource(device.previewStatusLabel()),
        batteryLevel = device.batteryLevel,
        isCharging = device.isCharging,
        statusColor = device.previewStatusColor(palette),
        showIcons = settings.showIcons,
        showMusic = settings.showMusic,
        iconText = "",
        deviceIcon = true,
        palette = palette,
    )
}

@Composable
@Suppress("LongParameterList")
private fun WidgetPreviewRow(
    title: String,
    subtitle: String,
    mediaTitle: String?,
    status: String,
    batteryLevel: Int?,
    isCharging: Boolean,
    statusColor: Color,
    showIcons: Boolean,
    showMusic: Boolean,
    iconText: String,
    deviceIcon: Boolean,
    palette: WidgetPreviewPalette,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcons) {
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .clip(if (deviceIcon) RoundedCornerShape(8.dp) else CircleShape)
                        .background(palette.accentContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (deviceIcon) {
                    Icon(
                        Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(17.dp),
                    )
                } else {
                    Text(
                        iconText,
                        color = palette.accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = palette.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = palette.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showMusic && !mediaTitle.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = palette.onSurfaceVariant,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        mediaTitle,
                        color = palette.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        WidgetPreviewTelemetry(
            status = status,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            statusColor = statusColor,
            palette = palette,
        )
    }
}

@Composable
private fun WidgetPreviewTelemetry(
    status: String,
    batteryLevel: Int?,
    isCharging: Boolean,
    statusColor: Color,
    palette: WidgetPreviewPalette,
) {
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(4.dp))
            Text(
                status,
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        if (batteryLevel != null) {
            WidgetBatteryIndicator(batteryLevel, isCharging, palette)
        }
    }
}

@Composable
private fun WidgetBatteryIndicator(
    batteryLevel: Int,
    isCharging: Boolean,
    palette: WidgetPreviewPalette,
) {
    val level = batteryLevel.coerceIn(0, 100)
    val batteryColor =
        when {
            isCharging -> palette.accent
            level <= 15 -> palette.batteryLow
            level <= 40 -> palette.away
            else -> palette.accent
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .width(19.dp)
                    .height(10.dp)
                    .border(1.dp, batteryColor, RoundedCornerShape(2.dp))
                    .padding(2.dp),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((level.coerceAtLeast(3) / 100f))
                    .background(batteryColor),
            )
            if (isCharging) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = palette.surface,
                    modifier = Modifier.align(Alignment.Center).size(8.dp),
                )
            }
        }
        Box(Modifier.width(2.dp).height(4.dp).background(batteryColor))
        Spacer(Modifier.width(3.dp))
        Text(
            "$level%",
            color = batteryColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun widgetPreviewPalette(theme: WidgetTheme): WidgetPreviewPalette {
    val dark =
        when (theme) {
            WidgetTheme.LIGHT -> false
            WidgetTheme.DARK -> true
            WidgetTheme.SYSTEM -> isSystemInDarkTheme()
        }
    return if (dark) {
        WidgetPreviewPalette(
            desktop = Color(0xFF46525A),
            surface = Color(0xFF1A1B1E),
            onSurface = Color(0xFFE5E2E6),
            onSurfaceVariant = Color(0xFFC7C6CA),
            accent = Color(0xFF73D8E2),
            accentContainer = Color(0xFF164C53),
            away = Color(0xFFFFB84D),
            batteryLow = Color(0xFFFFB4AB),
            divider = Color(0xFF45464A),
        )
    } else {
        WidgetPreviewPalette(
            desktop = Color(0xFFD4DDD9),
            surface = Color(0xFFF8F9FA),
            onSurface = Color(0xFF161719),
            onSurfaceVariant = Color(0xFF5D6065),
            accent = Color(0xFF007D8A),
            accentContainer = Color(0xFFD1F2F5),
            away = Color(0xFFB37700),
            batteryLow = Color(0xFFBA1A1A),
            divider = Color(0xFFDADDE1),
        )
    }
}

@Composable
private fun widgetPreviewUpdatedLabel(feedState: com.nekonf.nekostatus.core.model.WidgetFeedState): String {
    val timestamp = feedState.feed?.fetchedAtEpochMs
    val formatted = remember(timestamp) { timestamp?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } }
    return when {
        formatted == null -> stringResource(R.string.widget_settings_preview_updated_now)
        feedState.availability == WidgetAvailability.OFFLINE || feedState.availability == WidgetAvailability.ERROR ->
            stringResource(R.string.widget_settings_preview_cached_at, formatted)
        else -> stringResource(R.string.widget_settings_preview_updated_at, formatted)
    }
}

private fun WidgetUserStatus.previewStatusColor(palette: WidgetPreviewPalette): Color =
    when (userStatus?.lowercase()) {
        "away" -> palette.away
        "online" -> palette.accent
        else -> if (isOnline) palette.accent else palette.onSurfaceVariant
    }

private fun WidgetDeviceStatus.previewStatusColor(palette: WidgetPreviewPalette): Color =
    when (userStatus.lowercase()) {
        "away" -> palette.away
        "online" -> palette.accent
        else -> if (isOnline) palette.accent else palette.onSurfaceVariant
    }

private fun WidgetDeviceStatus.previewStatusLabel(): Int =
    when (userStatus.lowercase()) {
        "away" -> R.string.widget_user_away
        "online" -> R.string.widget_user_online
        "offline" -> R.string.widget_user_offline
        else -> if (isOnline) R.string.widget_user_online else R.string.widget_user_offline
    }

@Composable
@Suppress("LongParameterList")
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            supporting?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
    }
}

private fun WidgetAvailability.widgetStateMessage(): Int =
    when (this) {
        WidgetAvailability.LOADING -> R.string.widget_settings_loading
        WidgetAvailability.UNAUTHORIZED -> R.string.widget_settings_auth_required
        WidgetAvailability.UNAVAILABLE -> R.string.widget_settings_unavailable
        WidgetAvailability.OFFLINE -> R.string.widget_settings_offline
        WidgetAvailability.EMPTY -> R.string.widget_no_visible_users
        WidgetAvailability.ERROR -> R.string.widget_settings_error
        WidgetAvailability.DISABLED, WidgetAvailability.READY -> R.string.widget_settings_waiting
    }

private fun WidgetUserStatus.statusLabel(): Int =
    when (userStatus?.lowercase()) {
        "away" -> R.string.widget_user_away
        "online" -> R.string.widget_user_online
        "offline" -> R.string.widget_user_offline
        else -> if (isOnline) R.string.widget_user_online else R.string.widget_user_offline
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearancePage(
    state: SettingsUiState,
    onSetTheme: (String, Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(
                "system" to R.string.settings_theme_system,
                "light" to R.string.settings_theme_light,
                "dark" to R.string.settings_theme_dark,
            )
                .forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { onSetTheme(mode, state.dynamicColor) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) { Text(stringResource(label)) }
                }
        }
        NekoPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_dynamic_color_support), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.dynamicColor,
                    onCheckedChange = { onSetTheme(state.themeMode, it) },
                )
            }
        }
    }
}

@Composable
private fun PrivacyPage(modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(listOf(R.string.privacy_credentials, R.string.privacy_no_screenshots, R.string.privacy_minimal_data, R.string.privacy_logs)) {
            NekoPanel(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun UpdatePage(
    appVersion: String,
    settings: UpdateSettings,
    updateState: UpdateUiState,
    onAutomaticChecksChange: (Boolean) -> Unit,
    onAutomaticDownloadChange: (Boolean) -> Unit,
    onSelectSource: (UpdateSource) -> Unit,
    onSaveCustomRepository: (String, (Boolean) -> Unit) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedSource by rememberSaveable(settings.source) { mutableStateOf(settings.source) }
    var repositoryDraft by rememberSaveable(settings.customRepository) { mutableStateOf(settings.customRepository) }
    var repositoryTouched by rememberSaveable(settings.customRepository) { mutableStateOf(false) }
    var repositorySaveResult by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var notificationsEnabled by remember(context) { mutableStateOf(areUpdateNotificationsEnabled(context)) }
    val notificationSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            notificationsEnabled = areUpdateNotificationsEnabled(context)
        }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationsEnabled = areUpdateNotificationsEnabled(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val normalizedRepository = remember(repositoryDraft) { normalizeGitHubRepository(repositoryDraft) }
    val sourceReady =
        selectedSource == settings.source &&
            (selectedSource == UpdateSource.OFFICIAL || normalizedRepository == settings.customRepository)

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(UpdatePageTestTags.ROOT)
                .padding(horizontal = 18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_current_version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(appVersion, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_update_security), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.settings_update_automation),
                supporting = stringResource(R.string.settings_update_automation_support),
            )
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_update_auto_check),
                    supporting = stringResource(R.string.settings_update_auto_check_support),
                    checked = settings.automaticChecks,
                    onCheckedChange = onAutomaticChecksChange,
                    testTag = UpdatePageTestTags.AUTO_CHECK,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_update_auto_download),
                    supporting = stringResource(R.string.settings_update_auto_download_support),
                    checked = settings.automaticDownload,
                    onCheckedChange = onAutomaticDownloadChange,
                    testTag = UpdatePageTestTags.AUTO_DOWNLOAD,
                )
            }
        }
        if (!notificationsEnabled) {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_update_notifications_disabled),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.settings_update_notifications_disabled_support),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            notificationSettingsLauncher.launch(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(UpdatePageTestTags.NOTIFICATION_SETTINGS),
                    ) {
                        Text(stringResource(R.string.settings_update_open_notification_settings))
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.settings_update_source),
                supporting = stringResource(R.string.settings_update_source_support),
            )
        }
        item {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .testTag(UpdatePageTestTags.SOURCE_SELECTOR),
            ) {
                UpdateSource.entries.forEachIndexed { index, source ->
                    SegmentedButton(
                        selected = selectedSource == source,
                        onClick = {
                            selectedSource = source
                            repositoryTouched = false
                            repositorySaveResult = null
                            onSelectSource(source)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, UpdateSource.entries.size),
                        modifier =
                            Modifier.testTag(
                                if (source == UpdateSource.OFFICIAL) {
                                    UpdatePageTestTags.SOURCE_OFFICIAL
                                } else {
                                    UpdatePageTestTags.SOURCE_CUSTOM
                                },
                            ),
                    ) {
                        Text(
                            stringResource(
                                if (source == UpdateSource.OFFICIAL) {
                                    R.string.settings_update_source_official
                                } else {
                                    R.string.settings_update_source_custom
                                },
                            ),
                        )
                    }
                }
            }
        }
        if (selectedSource == UpdateSource.OFFICIAL) {
            item {
                Text(
                    text = UpdateSettings.OFFICIAL_REPOSITORY,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = repositoryDraft,
                        onValueChange = {
                            repositoryDraft = it
                            repositoryTouched = true
                            repositorySaveResult = null
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(UpdatePageTestTags.REPOSITORY_INPUT),
                        label = { Text(stringResource(R.string.settings_update_custom_repository)) },
                        placeholder = { Text(UpdateSettings.OFFICIAL_REPOSITORY) },
                        singleLine = true,
                        isError = repositoryTouched && normalizedRepository == null,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (repositoryTouched && normalizedRepository == null) {
                                        R.string.settings_update_custom_repository_error
                                    } else {
                                        R.string.settings_update_custom_repository_support
                                    },
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            repositoryTouched = true
                            onSaveCustomRepository(repositoryDraft) { repositorySaveResult = it }
                        },
                        enabled = repositoryDraft.isNotBlank(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(UpdatePageTestTags.REPOSITORY_SAVE),
                    ) {
                        Text(stringResource(R.string.settings_update_custom_repository_save))
                    }
                    repositorySaveResult?.let { saved ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text =
                                stringResource(
                                    if (saved) {
                                        R.string.settings_update_custom_repository_saved
                                    } else {
                                        R.string.settings_update_custom_repository_save_failed
                                    },
                                ),
                            color =
                                if (saved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_update_custom_repository_security),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.settings_update_status))
        }
        item {
            UpdateStatusPanel(updateState, Modifier.testTag(UpdatePageTestTags.STATUS))
        }
        if (updateState.status == UpdateStatus.AVAILABLE && !updateState.message.isNullOrBlank()) {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_update_release_notes), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = updateState.message.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            UpdateActions(
                state = updateState,
                enabled = sourceReady,
                onCheck = onCheck,
                onDownload = onDownload,
                onInstall = onInstall,
            )
        }
    }
}

@Composable
private fun UpdateStatusPanel(
    state: UpdateUiState,
    modifier: Modifier = Modifier,
) {
    NekoPanel(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.showsIndeterminateProgress()) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector =
                        when (state.status) {
                            UpdateStatus.ERROR -> Icons.Rounded.BugReport
                            UpdateStatus.LATEST, UpdateStatus.READY -> Icons.Rounded.Security
                            UpdateStatus.AVAILABLE -> Icons.Rounded.SystemUpdate
                            else -> Icons.Rounded.Refresh
                        },
                    contentDescription = null,
                    tint =
                        if (state.status == UpdateStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    updateStatusTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    updateStatusSupportingText(state),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.lastCheckedEpochMs?.let { checkedAt ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    R.string.settings_update_last_checked,
                    remember(checkedAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(checkedAt)) },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun UpdateUiState.showsIndeterminateProgress(): Boolean =
    isRefreshing ||
        when (status) {
            UpdateStatus.CHECKING,
            UpdateStatus.DOWNLOADING,
            UpdateStatus.VERIFYING,
            -> true
            else -> false
        }

@Composable
private fun updateStatusTitle(state: UpdateUiState): String =
    when (state.status) {
        UpdateStatus.IDLE -> stringResource(R.string.settings_update_status_idle)
        UpdateStatus.CHECKING -> stringResource(R.string.settings_update_status_checking)
        UpdateStatus.LATEST -> stringResource(R.string.settings_update_status_latest)
        UpdateStatus.AVAILABLE ->
            stringResource(
                R.string.settings_update_status_available,
                state.version ?: stringResource(R.string.settings_update_unknown_version),
            )
        UpdateStatus.DOWNLOADING ->
            stringResource(
                R.string.settings_update_status_downloading,
                state.version ?: stringResource(R.string.settings_update_unknown_version),
            )
        UpdateStatus.VERIFYING -> stringResource(R.string.settings_update_status_verifying)
        UpdateStatus.READY ->
            stringResource(
                R.string.settings_update_status_ready,
                state.version ?: stringResource(R.string.settings_update_unknown_version),
            )
        UpdateStatus.ERROR -> stringResource(updateErrorTitleResource(state.failureStage))
    }

@Composable
private fun updateStatusSupportingText(state: UpdateUiState): String =
    if (state.isRefreshing && state.status != UpdateStatus.CHECKING) {
        stringResource(R.string.settings_update_status_checking_support)
    } else {
        updateStatusSupportingTextForStatus(state)
    }

@Composable
private fun updateStatusSupportingTextForStatus(state: UpdateUiState): String =
    when (state.status) {
        UpdateStatus.IDLE -> stringResource(R.string.settings_update_status_idle_support)
        UpdateStatus.CHECKING -> stringResource(R.string.settings_update_status_checking_support)
        UpdateStatus.LATEST -> stringResource(R.string.settings_update_status_latest_support)
        UpdateStatus.AVAILABLE -> stringResource(R.string.settings_update_status_available_support)
        UpdateStatus.DOWNLOADING ->
            state.downloadProgressPercent?.let {
                stringResource(R.string.settings_update_download_progress, it.coerceIn(0, 100))
            } ?: stringResource(R.string.settings_update_status_downloading_support)
        UpdateStatus.VERIFYING -> stringResource(R.string.settings_update_status_verifying_support)
        UpdateStatus.READY -> stringResource(R.string.settings_update_status_ready_support)
        UpdateStatus.ERROR -> stringResource(updateErrorSupportingResource(state.failureStage))
    }

internal fun updateErrorTitleResource(stage: UpdateFailureStage?): Int =
    when (stage) {
        UpdateFailureStage.CHECK -> R.string.settings_update_status_error_check
        UpdateFailureStage.DOWNLOAD -> R.string.settings_update_status_error_download
        UpdateFailureStage.VERIFY -> R.string.settings_update_status_error_verify
        null -> R.string.settings_update_status_error
    }

internal fun updateErrorSupportingResource(stage: UpdateFailureStage?): Int =
    when (stage) {
        UpdateFailureStage.CHECK -> R.string.settings_update_status_error_check_support
        UpdateFailureStage.DOWNLOAD -> R.string.settings_update_status_error_download_support
        UpdateFailureStage.VERIFY -> R.string.settings_update_status_error_verify_support
        null -> R.string.settings_update_status_error_support
    }

@Composable
private fun UpdateActions(
    state: UpdateUiState,
    enabled: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!enabled) {
            Text(
                stringResource(R.string.settings_update_save_source_first),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state.status) {
            UpdateStatus.CHECKING ->
                PrimaryAction(
                    text = stringResource(R.string.settings_update_status_checking),
                    onClick = onCheck,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = false,
                    icon = Icons.Rounded.Refresh,
                )
            UpdateStatus.AVAILABLE -> {
                PrimaryAction(
                    text = stringResource(R.string.settings_update_download),
                    onClick = onDownload,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = enabled,
                    icon = Icons.Rounded.SystemUpdate,
                )
                CheckAgainButton(onCheck, enabled, state.isRefreshing)
            }
            UpdateStatus.DOWNLOADING ->
                PrimaryAction(
                    text = stringResource(R.string.settings_update_downloading),
                    onClick = onDownload,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = false,
                    icon = Icons.Rounded.SystemUpdate,
                )
            UpdateStatus.VERIFYING ->
                PrimaryAction(
                    text = stringResource(R.string.settings_update_verifying),
                    onClick = onDownload,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = false,
                    icon = Icons.Rounded.Security,
                )
            UpdateStatus.READY -> {
                PrimaryAction(
                    text = stringResource(R.string.settings_update_install),
                    onClick = onInstall,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = enabled,
                    icon = Icons.Rounded.Security,
                )
                CheckAgainButton(onCheck, enabled, state.isRefreshing)
            }
            else ->
                PrimaryAction(
                    text =
                        stringResource(
                            if (state.status == UpdateStatus.IDLE) {
                                R.string.settings_check_updates
                            } else {
                                R.string.settings_update_check_again
                            },
                        ),
                    onClick = onCheck,
                    modifier = Modifier.testTag(UpdatePageTestTags.MAIN_ACTION),
                    enabled = enabled,
                    icon = Icons.Rounded.Refresh,
                )
        }
        state.releaseUrl?.let { releaseUrl ->
            OutlinedButton(
                onClick = { runCatching { uriHandler.openUri(releaseUrl) } },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(UpdatePageTestTags.VIEW_RELEASE),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_update_view_github))
            }
        }
    }
}

@Composable
private fun CheckAgainButton(
    onCheck: () -> Unit,
    enabled: Boolean,
    refreshing: Boolean,
) {
    OutlinedButton(
        onClick = onCheck,
        enabled = enabled && !refreshing,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(UpdatePageTestTags.CHECK_AGAIN),
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (refreshing) {
                    R.string.settings_update_status_checking
                } else {
                    R.string.settings_update_check_again
                },
            ),
        )
    }
}

private fun areUpdateNotificationsEnabled(context: Context): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val permissionGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val updateChannelEnabled =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationManager.getNotificationChannel(UPDATE_NOTIFICATION_CHANNEL)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    return notificationManager.areNotificationsEnabled() && permissionGranted && updateChannelEnabled
}

@Composable
private fun DiagnosticsPage(
    viewModel: SettingsViewModel,
    modifier: Modifier,
) {
    val logs by viewModel.logs.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.settings_diagnostics_support), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = viewModel::clearDiagnostics, enabled = logs.isNotEmpty()) {
                Text(stringResource(R.string.settings_clear_logs))
            }
        }
        if (logs.isEmpty()) {
            item { Text(stringResource(R.string.settings_no_logs), modifier = Modifier.padding(vertical = 28.dp)) }
        } else {
            items(logs, key = { it.id }) { log ->
                NekoPanel(Modifier.fillMaxWidth()) {
                    Text("${log.level} · ${log.category}", style = MaterialTheme.typography.labelLarge)
                    Text(log.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AboutPage(
    appVersion: String,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Neko Status", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(appVersion, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.settings_about_support), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RedirectPage(
    message: Int,
    button: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(button)) }
    }
}

private val SettingsDestination.title: Int
    get() =
        when (this) {
            SettingsDestination.ACCOUNT -> R.string.settings_account
            SettingsDestination.SERVER -> R.string.settings_server
            SettingsDestination.REPORTING -> R.string.settings_reporting
            SettingsDestination.PERMISSIONS -> R.string.settings_permissions
            SettingsDestination.WIDGETS -> R.string.settings_widgets
            SettingsDestination.APPEARANCE -> R.string.settings_appearance
            SettingsDestination.PRIVACY -> R.string.settings_privacy
            SettingsDestination.UPDATES -> R.string.settings_updates
            SettingsDestination.DIAGNOSTICS -> R.string.settings_diagnostics
            SettingsDestination.ABOUT -> R.string.settings_about
        }
