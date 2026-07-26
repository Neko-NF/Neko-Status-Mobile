package com.nekonf.nekostatus.feature.activity

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
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nekonf.nekostatus.core.designsystem.FeatureUnavailable
import com.nekonf.nekostatus.core.designsystem.NekoPanel
import com.nekonf.nekostatus.core.designsystem.NekoTheme
import com.nekonf.nekostatus.core.model.ServerCapabilities

enum class ActivityTab { HISTORY, ANNOUNCEMENTS, FOLLOWS }

enum class ActivityContentState { LOADING, CONTENT, EMPTY, ERROR, OFFLINE }

data class ActivityEntry(
    val title: String,
    val supporting: String,
    val timestamp: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    capabilities: ServerCapabilities,
    modifier: Modifier = Modifier,
    contentState: ActivityContentState = ActivityContentState.EMPTY,
    entries: List<ActivityEntry> = emptyList(),
) {
    var selected by remember { mutableStateOf(ActivityTab.HISTORY) }
    val enabled =
        when (selected) {
            ActivityTab.HISTORY -> capabilities.history
            ActivityTab.ANNOUNCEMENTS -> capabilities.announcements
            ActivityTab.FOLLOWS -> capabilities.activity
        }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.activity_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityTab.entries.forEach { tab ->
                FilterChip(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    label = { Text(stringResource(tab.label)) },
                    leadingIcon = { Icon(tab.icon, contentDescription = null) },
                )
            }
        }
        if (!enabled) {
            FeatureUnavailable(
                title = stringResource(R.string.activity_unavailable_title),
                message = stringResource(R.string.activity_unavailable_message),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        when (contentState) {
            ActivityContentState.LOADING ->
                StateMessage(
                    title = stringResource(R.string.activity_loading),
                    icon = null,
                    showProgress = true,
                )
            ActivityContentState.EMPTY ->
                StateMessage(
                    title = stringResource(R.string.activity_empty),
                    icon = selected.icon,
                )
            ActivityContentState.ERROR ->
                StateMessage(
                    title = stringResource(R.string.activity_error),
                    icon = Icons.Rounded.ErrorOutline,
                )
            ActivityContentState.OFFLINE ->
                StateMessage(
                    title = stringResource(R.string.activity_offline),
                    icon = Icons.Rounded.CloudOff,
                )
            ActivityContentState.CONTENT ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries) { entry ->
                        NekoPanel(Modifier.fillMaxWidth()) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(entry.supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(entry.timestamp, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
        }
    }
}

@Composable
private fun StateMessage(
    title: String,
    icon: ImageVector?,
    showProgress: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) CircularProgressIndicator()
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ActivityTab.label: Int
    get() =
        when (this) {
            ActivityTab.HISTORY -> R.string.activity_history
            ActivityTab.ANNOUNCEMENTS -> R.string.activity_announcements
            ActivityTab.FOLLOWS -> R.string.activity_follows
        }

private val ActivityTab.icon: ImageVector
    get() =
        when (this) {
            ActivityTab.HISTORY -> Icons.Rounded.History
            ActivityTab.ANNOUNCEMENTS -> Icons.Rounded.Campaign
            ActivityTab.FOLLOWS -> Icons.Rounded.FavoriteBorder
        }

@Preview(showBackground = true)
@Composable
private fun ActivityPreview() {
    NekoTheme {
        ActivityScreen(
            capabilities = ServerCapabilities(history = true, announcements = true, activity = true),
            contentState = ActivityContentState.CONTENT,
            entries = listOf(ActivityEntry("状态已更新", "手机正在使用 Neko Status", "刚刚")),
        )
    }
}
