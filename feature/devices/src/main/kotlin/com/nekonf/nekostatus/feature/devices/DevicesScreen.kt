package com.nekonf.nekostatus.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekonf.nekostatus.core.designsystem.NekoPanel
import com.nekonf.nekostatus.core.designsystem.NekoTone
import com.nekonf.nekostatus.core.designsystem.PrimaryAction
import com.nekonf.nekostatus.core.designsystem.SectionHeader
import com.nekonf.nekostatus.core.designsystem.StatusPill

enum class PermissionKind { NOTIFICATIONS, USAGE_ACCESS, MEDIA_ACCESS, ACCESSIBILITY }

data class PermissionStatus(
    val kind: PermissionKind,
    val granted: Boolean,
)

@Composable
@Suppress("LongParameterList")
fun DevicesScreen(
    deviceName: String,
    permissions: List<PermissionStatus>,
    onPermissionClick: (PermissionKind) -> Unit,
    onOpenWidgetSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onboarding: Boolean = false,
    onCompleteOnboarding: () -> Unit = {},
) {
    val visiblePermissions =
        if (onboarding) {
            permissions.firstOrNull { !it.granted }?.let(::listOf) ?: emptyList()
        } else {
            permissions
        }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (onboarding) R.string.permissions_title else R.string.devices_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(if (onboarding) R.string.permissions_onboarding_support else R.string.devices_support),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!onboarding) {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.devices_this_device), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(stringResource(R.string.devices_connected), NekoTone.SUCCESS)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.permissions_section)) }
        }
        items(visiblePermissions) { permission ->
            PermissionCard(permission, onPermissionClick)
        }
        if (onboarding && visiblePermissions.isEmpty()) {
            item {
                NekoPanel(Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.permissions_ready), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.permissions_ready_support), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (onboarding) {
            item {
                PrimaryAction(
                    text = stringResource(R.string.permissions_continue),
                    onClick = onCompleteOnboarding,
                )
                Spacer(Modifier.height(18.dp))
            }
        } else {
            item {
                SectionHeader(stringResource(R.string.widgets_title))
                NekoPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.widgets_status_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.widgets_status_support), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenWidgetSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.widgets_open_settings))
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionStatus,
    onPermissionClick: (PermissionKind) -> Unit,
) {
    NekoPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(permission.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(permission.kind.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(permission.kind.support), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(
                text = stringResource(if (permission.granted) R.string.permissions_granted else R.string.permissions_not_granted),
                tone = if (permission.granted) NekoTone.SUCCESS else NekoTone.WARNING,
            )
        }
        if (!permission.granted) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onPermissionClick(permission.kind) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permissions_open_system))
            }
        }
    }
}

private val PermissionKind.title: Int
    get() =
        when (this) {
            PermissionKind.NOTIFICATIONS -> R.string.permission_notifications
            PermissionKind.USAGE_ACCESS -> R.string.permission_usage
            PermissionKind.MEDIA_ACCESS -> R.string.permission_media
            PermissionKind.ACCESSIBILITY -> R.string.permission_accessibility
        }

private val PermissionKind.support: Int
    get() =
        when (this) {
            PermissionKind.NOTIFICATIONS -> R.string.permission_notifications_support
            PermissionKind.USAGE_ACCESS -> R.string.permission_usage_support
            PermissionKind.MEDIA_ACCESS -> R.string.permission_media_support
            PermissionKind.ACCESSIBILITY -> R.string.permission_accessibility_support
        }

private val PermissionKind.icon: ImageVector
    get() =
        when (this) {
            PermissionKind.NOTIFICATIONS -> Icons.Rounded.Notifications
            PermissionKind.USAGE_ACCESS -> Icons.Rounded.Apps
            PermissionKind.MEDIA_ACCESS -> Icons.Rounded.PlayCircle
            PermissionKind.ACCESSIBILITY -> Icons.Rounded.AccessibilityNew
        }
