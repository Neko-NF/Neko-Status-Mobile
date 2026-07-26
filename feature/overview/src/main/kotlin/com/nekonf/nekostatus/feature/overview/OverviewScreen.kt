package com.nekonf.nekostatus.feature.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nekonf.nekostatus.core.designsystem.NekoPanel
import com.nekonf.nekostatus.core.designsystem.NekoShapes
import com.nekonf.nekostatus.core.designsystem.NekoSpacing
import com.nekonf.nekostatus.core.designsystem.NekoTone
import com.nekonf.nekostatus.core.designsystem.PrimaryAction
import com.nekonf.nekostatus.core.designsystem.SectionHeader
import com.nekonf.nekostatus.core.designsystem.StatusPill
import com.nekonf.nekostatus.core.model.ScreenState
import java.text.DateFormat
import java.util.Date

@Composable
fun OverviewScreen(
    onStartReporting: () -> Unit,
    onStopReporting: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val health by viewModel.health.collectAsState()
    val credential by viewModel.deviceCredential.collectAsState()
    val snapshot = health.currentSnapshot
    val lastSuccess =
        health.lastSuccessEpochMs?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: stringResource(R.string.overview_never)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = NekoSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(NekoSpacing.Large),
    ) {
        item {
            Spacer(Modifier.height(NekoSpacing.Small))
            Column(verticalArrangement = Arrangement.spacedBy(NekoSpacing.XSmall)) {
                Text(stringResource(R.string.overview_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    credential?.deviceName ?: stringResource(R.string.overview_device_unbound),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                StatusPill(
                    text = stringResource(if (health.isRunning) R.string.overview_running else R.string.overview_stopped),
                    tone = if (health.isRunning) NekoTone.SUCCESS else NekoTone.NEUTRAL,
                )
            }
        }
        item {
            ReportingControl(
                running = health.isRunning,
                canStart = credential != null,
                onStartReporting = onStartReporting,
                onStopReporting = onStopReporting,
            )
        }
        item {
            Spacer(Modifier.height(NekoSpacing.Small))
            SectionHeader(stringResource(R.string.overview_current_state))
        }
        item {
            NekoPanel(Modifier.fillMaxWidth()) {
                OverviewMetricRow(
                    label = stringResource(R.string.overview_battery),
                    value = snapshot?.let { "${it.batteryLevel}%" } ?: "--",
                    supporting =
                        if (snapshot?.isCharging == true) {
                            stringResource(R.string.overview_battery_charging)
                        } else {
                            null
                        },
                    icon = if (snapshot?.isCharging == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
                    tint =
                        if ((snapshot?.batteryLevel ?: 100) < 20) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                )
                OverviewDivider()
                OverviewMetricRow(
                    label = stringResource(R.string.overview_screen),
                    value = snapshot?.screenState?.asDisplayText() ?: "--",
                    icon = Icons.Rounded.Lock,
                    tint = MaterialTheme.colorScheme.primary,
                )
                OverviewDivider()
                OverviewMetricRow(
                    label = stringResource(R.string.overview_foreground_app),
                    value = snapshot?.appName ?: stringResource(R.string.overview_waiting_snapshot),
                    icon = Icons.Rounded.Apps,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            SyncHealthRow(lastSuccess = lastSuccess)
        }
        if (health.lastErrorMessage != null) {
            item {
                ReportingAttentionNotice()
            }
        }
        item {
            TextButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.overview_review_permissions))
            }
            Spacer(Modifier.height(NekoSpacing.Small))
        }
    }
}

@Composable
private fun ReportingControl(
    running: Boolean,
    canStart: Boolean,
    onStartReporting: () -> Unit,
    onStopReporting: () -> Unit,
) {
    NekoPanel(Modifier.fillMaxWidth()) {
        Text(
            stringResource(if (running) R.string.overview_reporting_active else R.string.overview_reporting_paused),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(NekoSpacing.XSmall))
        Text(
            stringResource(if (running) R.string.overview_active_support else R.string.overview_paused_support),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NekoSpacing.Large))
        if (running) {
            OutlinedButton(
                onClick = onStopReporting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = NekoShapes.Control,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.72f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(NekoSpacing.Small))
                Text(stringResource(R.string.overview_stop))
            }
        } else {
            PrimaryAction(
                text = stringResource(R.string.overview_start),
                onClick = onStartReporting,
                enabled = canStart,
                icon = Icons.Rounded.PlayArrow,
            )
        }
    }
}

@Composable
private fun OverviewMetricRow(
    label: String,
    value: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NekoSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoSpacing.XSmall)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OverviewDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = NekoSpacing.Medium),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SyncHealthRow(lastSuccess: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NekoSpacing.XSmall),
        horizontalArrangement = Arrangement.spacedBy(NekoSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(stringResource(R.string.overview_last_success), style = MaterialTheme.typography.titleMedium)
            Text(lastSuccess, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportingAttentionNotice() {
    NekoPanel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(NekoSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column {
                Text(stringResource(R.string.overview_report_error), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.overview_review_permissions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScreenState.asDisplayText(): String =
    stringResource(
        when (this) {
            ScreenState.ON -> R.string.overview_screen_on
            ScreenState.LOCKED -> R.string.overview_screen_locked
            ScreenState.OFF -> R.string.overview_screen_off
        },
    )
