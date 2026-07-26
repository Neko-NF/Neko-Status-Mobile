package com.nekonf.nekostatus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class NekoTone { PRIMARY, SUCCESS, WARNING, ERROR, NEUTRAL }

@Composable
private fun toneColor(tone: NekoTone): Color =
    when (tone) {
        NekoTone.PRIMARY -> LocalNekoStatusColors.current.primary
        NekoTone.SUCCESS -> LocalNekoStatusColors.current.success
        NekoTone.WARNING -> LocalNekoStatusColors.current.warning
        NekoTone.ERROR -> LocalNekoStatusColors.current.error
        NekoTone.NEUTRAL -> LocalNekoStatusColors.current.neutral
    }

@Composable
fun StatusPill(
    text: String,
    tone: NekoTone,
    modifier: Modifier = Modifier,
) {
    val color = toneColor(tone)
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = NekoShapes.Status,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NekoSpacing.Small, vertical = NekoSpacing.XSmall),
            horizontalArrangement = Arrangement.spacedBy(NekoSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun NekoPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = NekoShapes.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(NekoSpacing.Large), content = content)
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    tone: NekoTone = NekoTone.PRIMARY,
    modifier: Modifier = Modifier,
) {
    val color = toneColor(tone)
    NekoPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = NekoShapes.Panel, color = color.copy(alpha = 0.12f), contentColor = color) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(NekoSpacing.Small).size(20.dp))
            }
            Column {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SettingsRow(
    title: String,
    supporting: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing ?: { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(Color.Transparent),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@Composable
fun FeatureUnavailable(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = NekoSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = NekoShapes.Control,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(NekoSpacing.Small))
        }
        Text(text)
    }
}
