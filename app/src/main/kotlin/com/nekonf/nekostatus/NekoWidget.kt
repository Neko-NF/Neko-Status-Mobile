package com.nekonf.nekostatus

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import com.nekonf.nekostatus.core.data.WidgetCacheSnapshot
import com.nekonf.nekostatus.core.data.WidgetFeedStore
import com.nekonf.nekostatus.core.data.WidgetSettingsStore
import com.nekonf.nekostatus.core.model.WidgetDeviceStatus
import com.nekonf.nekostatus.core.model.WidgetDisplayMode
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.WidgetTheme
import com.nekonf.nekostatus.core.model.WidgetUserStatus
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

private data class WidgetPalette(
    val backgroundDrawable: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val away: Int,
    val batteryLow: Int,
)

private val LightWidgetPalette =
    WidgetPalette(
        backgroundDrawable = R.drawable.widget_background_light,
        onSurface = 0xFF161719.toInt(),
        onSurfaceVariant = 0xFF5D6065.toInt(),
        primary = 0xFF007D8A.toInt(),
        away = 0xFFB37700.toInt(),
        batteryLow = 0xFFBA1A1A.toInt(),
    )
private val DarkWidgetPalette =
    WidgetPalette(
        backgroundDrawable = R.drawable.widget_background_dark,
        onSurface = 0xFFE5E2E6.toInt(),
        onSurfaceVariant = 0xFFC7C6CA.toInt(),
        primary = 0xFF73D8E2.toInt(),
        away = 0xFFFFB84D.toInt(),
        batteryLow = 0xFFFFB4AB.toInt(),
    )

internal fun statusDevicePages(
    devices: List<WidgetDeviceStatus>,
    selectedDeviceIds: List<String>,
): List<List<WidgetDeviceStatus>> {
    if (devices.isEmpty()) return emptyList()
    val requestedIds = selectedDeviceIds.filter(String::isNotBlank).distinct().take(2)
    val pageSize = requestedIds.size.takeIf { it > 0 } ?: minOf(2, devices.size)
    val selected =
        requestedIds.mapNotNull { id -> devices.firstOrNull { it.deviceId == id } }
    val firstPage = selected.ifEmpty { devices.take(pageSize) }
    val remaining = devices.filterNot { device -> firstPage.any { it.deviceId == device.deviceId } }
    return listOf(firstPage) + remaining.chunked(pageSize)
}

internal fun screenshotDeviceSequence(
    devices: List<WidgetDeviceStatus>,
    targetDeviceId: String?,
): List<WidgetDeviceStatus> {
    val available =
        devices.filter {
            !it.screenshotThumbnailUrl.isNullOrBlank() || !it.screenshotUrl.isNullOrBlank()
        }
    val target = available.firstOrNull { it.deviceId == targetDeviceId }
    return listOfNotNull(target) + available.filterNot { it.deviceId == target?.deviceId }
}

private object WidgetPageStateStore {
    private const val PREFERENCES = "neko-widget-page-state"

    fun current(
        context: Context,
        provider: String,
        appWidgetId: Int,
        pageCount: Int,
    ): Int {
        if (pageCount <= 1) return 0
        val stored =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getInt("$provider:$appWidgetId", 0)
        return stored.mod(pageCount)
    }

    fun advance(
        context: Context,
        provider: String,
        appWidgetId: Int,
        pageCount: Int,
    ) {
        if (pageCount <= 1) return
        val next = (current(context, provider, appWidgetId, pageCount) + 1).mod(pageCount)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt("$provider:$appWidgetId", next)
            .apply()
    }

    fun clear(
        context: Context,
        provider: String,
        appWidgetIds: IntArray,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply { appWidgetIds.forEach { remove("$provider:$it") } }
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

private fun widgetPalette(
    context: Context,
    settings: WidgetSettings,
): WidgetPalette {
    val systemDark =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    return when (settings.theme) {
        WidgetTheme.LIGHT -> LightWidgetPalette
        WidgetTheme.DARK -> DarkWidgetPalette
        WidgetTheme.SYSTEM -> if (systemDark) DarkWidgetPalette else LightWidgetPalette
    }
}

object NekoWidgetRenderer {
    private const val PAGE_PROVIDER = "status"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, NekoWidgetReceiver::class.java)
        manager.getAppWidgetIds(component).forEach { appWidgetId ->
            manager.updateAppWidget(appWidgetId, build(context, appWidgetId))
        }
    }

    fun resetPagesAndUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NekoWidgetReceiver::class.java))
        WidgetPageStateStore.clear(context, PAGE_PROVIDER, ids)
        update(context, manager, ids)
    }

    fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId -> manager.updateAppWidget(appWidgetId, build(context, appWidgetId)) }
    }

    fun advance(
        context: Context,
        appWidgetId: Int,
    ) {
        val settings = WidgetSettingsStore.read(context)
        val cache = WidgetFeedStore.read(context)
        val pageCount = statusPages(settings, cache).size
        WidgetPageStateStore.advance(context, PAGE_PROVIDER, appWidgetId, pageCount)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, build(context, appWidgetId))
    }

    fun clearPages(
        context: Context,
        appWidgetIds: IntArray,
    ) = WidgetPageStateStore.clear(context, PAGE_PROVIDER, appWidgetIds)

    fun clearAllPages(context: Context) {
        WidgetPageStateStore.clearAll(context)
    }

    private fun build(
        context: Context,
        appWidgetId: Int,
    ): RemoteViews {
        val settings = WidgetSettingsStore.read(context)
        val cache = WidgetFeedStore.read(context)
        val palette = widgetPalette(context, settings)
        return RemoteViews(context.packageName, R.layout.widget_layout).apply {
            applyPalette(palette, settings.backgroundOpacityPercent)
            setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_refresh_container, refreshIntent(context))
            setViewVisibility(R.id.widget_switch, View.GONE)
            setRefreshFeedback(WidgetRefreshFeedbackStore.isRefreshing(context))
            setTextViewText(R.id.widget_updated, cache.updatedLabel(context))
            renderContent(context, appWidgetId, settings, cache, palette)
        }
    }

    private fun RemoteViews.renderContent(
        context: Context,
        appWidgetId: Int,
        settings: WidgetSettings,
        cache: WidgetCacheSnapshot,
        palette: WidgetPalette,
    ) {
        val feed = cache.feed
        when {
            !settings.enabled -> showMessage(context.getString(R.string.widget_not_enabled))
            feed == null -> showMessage(context.getString(cache.errorCode.widgetErrorMessage()))
            feed.users.isEmpty() -> showMessage(context.getString(R.string.widget_no_visible_users))
            settings.displayMode == WidgetDisplayMode.SINGLE -> {
                val user =
                    feed.users.firstOrNull { it.userId == settings.targetUserId }
                        ?: feed.users.first()
                setTextViewText(R.id.widget_title, user.username)
                val pages = statusDevicePages(user.devices, settings.selectedDeviceIds)
                val pageIndex = WidgetPageStateStore.current(context, PAGE_PROVIDER, appWidgetId, pages.size)
                val devices = pages.getOrNull(pageIndex).orEmpty()
                if (settings.showDeviceSwitcher && pages.size > 1) {
                    setViewVisibility(R.id.widget_switch, View.VISIBLE)
                    setOnClickPendingIntent(R.id.widget_switch, switchIntent(context, appWidgetId))
                }
                if (devices.isEmpty()) {
                    showMessage(context.getString(R.string.widget_no_devices))
                } else {
                    showRows()
                    renderDeviceRow(context, R.id.widget_row_one, devices[0], settings, palette)
                    if (devices.size > 1) {
                        renderDeviceRow(context, R.id.widget_row_two, devices[1], settings, palette)
                    } else {
                        setViewVisibility(R.id.widget_row_two, View.GONE)
                        setViewVisibility(R.id.widget_row_divider, View.GONE)
                    }
                }
            }
            else -> {
                setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                showRows()
                val users = feed.users.take(2)
                renderUserRow(context, R.id.widget_row_one, users[0], settings, palette)
                if (users.size > 1) {
                    renderUserRow(context, R.id.widget_row_two, users[1], settings, palette)
                } else {
                    setViewVisibility(R.id.widget_row_two, View.GONE)
                    setViewVisibility(R.id.widget_row_divider, View.GONE)
                }
            }
        }
    }

    private fun statusPages(
        settings: WidgetSettings,
        cache: WidgetCacheSnapshot,
    ): List<List<WidgetDeviceStatus>> {
        if (settings.displayMode != WidgetDisplayMode.SINGLE) return emptyList()
        val user =
            cache.feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                ?: cache.feed?.users?.firstOrNull()
        return statusDevicePages(user?.devices.orEmpty(), settings.selectedDeviceIds)
    }

    private fun RemoteViews.renderUserRow(
        context: Context,
        rowId: Int,
        user: WidgetUserStatus,
        settings: WidgetSettings,
        palette: WidgetPalette,
    ) {
        val ids = rowIds(rowId)
        val device = user.devices.firstOrNull()
        renderLeadingImage(context, ids.icon, user.avatarUrl, settings.showIcons, R.drawable.neko_legacy_icon)
        setTextViewText(ids.title, user.username)
        setTextViewText(
            ids.subtitle,
            listOfNotNull(device?.deviceName, device?.appName?.takeIf(String::isNotBlank)).joinToString(" · ")
                .ifBlank { context.getString(R.string.widget_no_activity) },
        )
        renderMedia(ids.media, device?.media?.title, settings.showMusic)
        val status = context.getString(user.statusLabel())
        setTextViewText(ids.meta, status)
        setTextColor(ids.meta, user.statusColor(palette))
        setTextViewCompoundDrawables(ids.meta, user.statusDot(), 0, 0, 0)
        renderBattery(ids, device, palette)
    }

    private fun RemoteViews.renderDeviceRow(
        context: Context,
        rowId: Int,
        device: WidgetDeviceStatus,
        settings: WidgetSettings,
        palette: WidgetPalette,
    ) {
        val ids = rowIds(rowId)
        renderLeadingImage(context, ids.icon, device.appIconUrl, settings.showIcons, R.drawable.ic_neko_monochrome)
        setTextViewText(ids.title, device.deviceName)
        setTextViewText(
            ids.subtitle,
            device.appName.takeIf(String::isNotBlank) ?: context.getString(R.string.widget_no_activity),
        )
        renderMedia(ids.media, device.media?.title, settings.showMusic)
        val status = context.getString(device.statusLabel())
        setTextViewText(ids.meta, status)
        setTextColor(ids.meta, device.statusColor(palette))
        setTextViewCompoundDrawables(ids.meta, device.statusDot(), 0, 0, 0)
        renderBattery(ids, device, palette)
    }

    private fun RemoteViews.renderLeadingImage(
        context: Context,
        viewId: Int,
        source: String?,
        visible: Boolean,
        fallback: Int,
    ) {
        if (!visible) {
            setViewVisibility(viewId, View.GONE)
            return
        }
        setViewVisibility(viewId, View.VISIBLE)
        val targetSize = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
        val bitmap = WidgetImageCache.readScaled(context, source, targetSize)
        if (bitmap == null) setImageViewResource(viewId, fallback) else setImageViewBitmap(viewId, bitmap)
    }

    private fun RemoteViews.renderMedia(
        viewId: Int,
        title: String?,
        showMusic: Boolean,
    ) {
        if (showMusic && !title.isNullOrBlank()) {
            setTextViewText(viewId, title)
            setViewVisibility(viewId, View.VISIBLE)
        } else {
            setViewVisibility(viewId, View.GONE)
        }
    }

    private fun RemoteViews.renderBattery(
        ids: WidgetRowIds,
        device: WidgetDeviceStatus?,
        palette: WidgetPalette,
    ) {
        if (device == null) {
            setViewVisibility(ids.batteryGroup, View.GONE)
            return
        }
        val color = device.batteryColor(palette)
        setViewVisibility(ids.batteryGroup, View.VISIBLE)
        setImageViewResource(ids.batteryIcon, device.batteryIcon())
        setInt(ids.batteryIcon, "setColorFilter", color)
        setTextViewText(ids.batteryText, "${device.batteryLevel}%")
        setTextColor(ids.batteryText, color)
    }

    private fun RemoteViews.showRows() {
        setViewVisibility(R.id.widget_message, View.GONE)
        setViewVisibility(R.id.widget_rows, View.VISIBLE)
        setViewVisibility(R.id.widget_row_one, View.VISIBLE)
        setViewVisibility(R.id.widget_row_two, View.VISIBLE)
        setViewVisibility(R.id.widget_row_divider, View.VISIBLE)
    }

    private fun RemoteViews.showMessage(message: String) {
        setTextViewText(R.id.widget_message, message)
        setViewVisibility(R.id.widget_message, View.VISIBLE)
        setViewVisibility(R.id.widget_rows, View.GONE)
    }

    private fun RemoteViews.setRefreshFeedback(refreshing: Boolean) {
        setViewVisibility(R.id.widget_refresh, if (refreshing) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.widget_refresh_progress, if (refreshing) View.VISIBLE else View.GONE)
    }

    private fun RemoteViews.applyPalette(
        palette: WidgetPalette,
        opacityPercent: Int,
    ) {
        setImageViewResource(R.id.widget_background, palette.backgroundDrawable)
        setInt(R.id.widget_background, "setImageAlpha", opacityPercent.coerceIn(50, 100) * 255 / 100)
        setTextColor(R.id.widget_title, palette.onSurface)
        setTextColor(R.id.widget_updated, palette.onSurfaceVariant)
        setTextColor(R.id.widget_message, palette.onSurfaceVariant)
        listOf(R.id.widget_row_one, R.id.widget_row_two).forEach { rowId ->
            val ids = rowIds(rowId)
            setTextColor(ids.title, palette.onSurface)
            setTextColor(ids.subtitle, palette.onSurfaceVariant)
            setTextColor(ids.media, palette.onSurfaceVariant)
            setTextColor(ids.meta, palette.onSurfaceVariant)
        }
    }

    private fun rowIds(rowId: Int): WidgetRowIds =
        if (rowId == R.id.widget_row_one) {
            WidgetRowIds(
                icon = R.id.widget_row_one_icon,
                title = R.id.widget_row_one_title,
                subtitle = R.id.widget_row_one_subtitle,
                media = R.id.widget_row_one_media,
                meta = R.id.widget_row_one_meta,
                batteryGroup = R.id.widget_row_one_battery_group,
                batteryIcon = R.id.widget_row_one_battery_icon,
                batteryText = R.id.widget_row_one_battery_text,
            )
        } else {
            WidgetRowIds(
                icon = R.id.widget_row_two_icon,
                title = R.id.widget_row_two_title,
                subtitle = R.id.widget_row_two_subtitle,
                media = R.id.widget_row_two_media,
                meta = R.id.widget_row_two_meta,
                batteryGroup = R.id.widget_row_two_battery_group,
                batteryIcon = R.id.widget_row_two_battery_icon,
                batteryText = R.id.widget_row_two_battery_text,
            )
        }

    private fun WidgetCacheSnapshot.updatedLabel(context: Context): String {
        val timestamp = feed?.fetchedAtEpochMs ?: lastAttemptEpochMs
        val formatted =
            timestamp?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                ?: context.getString(R.string.widget_never)
        return context.getString(if (errorCode == null) R.string.widget_updated_at else R.string.widget_cached_at, formatted)
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, NekoWidgetReceiver::class.java).setAction(NekoWidgetReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun switchIntent(
        context: Context,
        appWidgetId: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            100_000 + appWidgetId,
            Intent(context, NekoWidgetReceiver::class.java)
                .setAction(NekoWidgetReceiver.ACTION_SWITCH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

private data class WidgetRowIds(
    val icon: Int,
    val title: Int,
    val subtitle: Int,
    val media: Int,
    val meta: Int,
    val batteryGroup: Int,
    val batteryIcon: Int,
    val batteryText: Int,
)

private fun String?.widgetErrorMessage(): Int =
    when (this) {
        "HTTP_401", "HTTP_403", "MISSING_DEVICE_KEY" -> R.string.widget_auth_required
        "HTTP_404", "HTTP_501" -> R.string.widget_unavailable
        "NETWORK_ERROR" -> R.string.widget_offline_cache_empty
        else -> R.string.widget_waiting
    }

private fun WidgetUserStatus.statusLabel(): Int =
    when (userStatus?.lowercase()) {
        "away" -> R.string.widget_away
        "online" -> R.string.widget_online
        "offline" -> R.string.widget_offline
        else -> if (isOnline) R.string.widget_online else R.string.widget_offline
    }

private fun WidgetUserStatus.statusColor(palette: WidgetPalette): Int =
    when (userStatus?.lowercase()) {
        "away" -> palette.away
        "online" -> palette.primary
        else -> if (isOnline) palette.primary else palette.onSurfaceVariant
    }

private fun WidgetUserStatus.statusDot(): Int =
    when (userStatus?.lowercase()) {
        "away" -> R.drawable.widget_status_dot_away
        "online" -> R.drawable.widget_status_dot_online
        "offline" -> R.drawable.widget_status_dot_offline
        else -> if (isOnline) R.drawable.widget_status_dot_online else R.drawable.widget_status_dot_offline
    }

private fun WidgetDeviceStatus.statusLabel(): Int =
    when (userStatus.lowercase()) {
        "away" -> R.string.widget_away
        "online" -> R.string.widget_online
        "offline" -> R.string.widget_offline
        else -> if (isOnline) R.string.widget_online else R.string.widget_offline
    }

private fun WidgetDeviceStatus.statusColor(palette: WidgetPalette): Int =
    when (userStatus.lowercase()) {
        "away" -> palette.away
        "online" -> palette.primary
        else -> if (isOnline) palette.primary else palette.onSurfaceVariant
    }

private fun WidgetDeviceStatus.statusDot(): Int =
    when (userStatus.lowercase()) {
        "away" -> R.drawable.widget_status_dot_away
        "online" -> R.drawable.widget_status_dot_online
        "offline" -> R.drawable.widget_status_dot_offline
        else -> if (isOnline) R.drawable.widget_status_dot_online else R.drawable.widget_status_dot_offline
    }

private fun WidgetDeviceStatus.batteryIcon(): Int =
    when {
        isCharging -> R.drawable.ic_widget_battery_charging
        batteryLevel <= 5 -> R.drawable.ic_widget_battery_empty
        batteryLevel <= 20 -> R.drawable.ic_widget_battery_low
        batteryLevel <= 55 -> R.drawable.ic_widget_battery_medium
        batteryLevel <= 90 -> R.drawable.ic_widget_battery_high
        else -> R.drawable.ic_widget_battery_full
    }

private fun WidgetDeviceStatus.batteryColor(palette: WidgetPalette): Int =
    when {
        isCharging -> palette.primary
        batteryLevel <= 15 -> palette.batteryLow
        batteryLevel <= 40 -> palette.away
        else -> palette.primary
    }

object NekoSnapshotWidgetRenderer {
    private const val PAGE_PROVIDER = "snapshot"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, NekoSnapshotWidgetReceiver::class.java)
        manager.getAppWidgetIds(component).forEach { appWidgetId ->
            manager.updateAppWidget(appWidgetId, build(context, appWidgetId))
        }
    }

    fun resetPagesAndUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NekoSnapshotWidgetReceiver::class.java))
        WidgetPageStateStore.clear(context, PAGE_PROVIDER, ids)
        update(context, manager, ids)
    }

    fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId -> manager.updateAppWidget(appWidgetId, build(context, appWidgetId)) }
    }

    fun advance(
        context: Context,
        appWidgetId: Int,
    ) {
        val settings = WidgetSettingsStore.read(context)
        val cache = WidgetFeedStore.read(context)
        val pageCount = selectSnapshotDevices(cache, settings).devices.size
        WidgetPageStateStore.advance(context, PAGE_PROVIDER, appWidgetId, pageCount)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, build(context, appWidgetId))
    }

    fun clearPages(
        context: Context,
        appWidgetIds: IntArray,
    ) = WidgetPageStateStore.clear(context, PAGE_PROVIDER, appWidgetIds)

    private fun build(
        context: Context,
        appWidgetId: Int,
    ): RemoteViews {
        val settings = WidgetSettingsStore.read(context)
        val cache = WidgetFeedStore.read(context)
        val palette = widgetPalette(context, settings)
        return RemoteViews(context.packageName, R.layout.widget_snapshot_layout).apply {
            applySnapshotPalette(palette, settings.backgroundOpacityPercent)
            setOnClickPendingIntent(R.id.snapshot_widget_root, snapshotOpenAppIntent(context))
            setOnClickPendingIntent(R.id.snapshot_widget_refresh_container, snapshotRefreshIntent(context))
            setViewVisibility(R.id.snapshot_widget_switch, View.GONE)
            setSnapshotRefreshFeedback(WidgetRefreshFeedbackStore.isRefreshing(context))
            renderSnapshot(context, appWidgetId, settings, cache, palette)
        }
    }

    private fun RemoteViews.renderSnapshot(
        context: Context,
        appWidgetId: Int,
        settings: WidgetSettings,
        cache: WidgetCacheSnapshot,
        palette: WidgetPalette,
    ) {
        val feed = cache.feed
        val selection = selectSnapshotDevices(cache, settings)
        val pageIndex =
            WidgetPageStateStore.current(context, PAGE_PROVIDER, appWidgetId, selection.devices.size)
        val target = SnapshotTarget(selection.user, selection.devices.getOrNull(pageIndex))
        val user = target.user
        val device = target.device
        when {
            !settings.enabled -> showSnapshotMessage(context.getString(R.string.widget_not_enabled), showInfo = false)
            !settings.showScreenshot ->
                showSnapshotMessage(context.getString(R.string.widget_screenshot_not_enabled), showInfo = false)
            feed == null ->
                showSnapshotMessage(context.getString(cache.errorCode.widgetErrorMessage()), showInfo = false)
            user == null -> showSnapshotMessage(context.getString(R.string.widget_no_visible_users), showInfo = false)
            device == null -> showSnapshotMessage(context.getString(R.string.widget_no_devices), showInfo = false)
            else -> {
                if (settings.showDeviceSwitcher && selection.devices.size > 1) {
                    setViewVisibility(R.id.snapshot_widget_switch, View.VISIBLE)
                    setOnClickPendingIntent(R.id.snapshot_widget_switch, snapshotSwitchIntent(context, appWidgetId))
                }
                setTextViewText(R.id.snapshot_widget_title, user.username)
                setTextViewText(R.id.snapshot_widget_updated, device.snapshotUpdatedLabel(context, cache))
                renderSnapshotDevice(context, device, settings, palette)
                val screenshotRendered = renderSnapshotImage(context, device)
                setViewVisibility(
                    R.id.snapshot_widget_stale,
                    if (cache.errorCode == null || !screenshotRendered) View.GONE else View.VISIBLE,
                )
            }
        }
    }

    private fun RemoteViews.renderSnapshotImage(
        context: Context,
        device: WidgetDeviceStatus,
    ): Boolean {
        val source = device.screenshotThumbnailUrl ?: device.screenshotUrl
        val bitmap =
            WidgetImageCache.readFitted(
                context = context,
                source = source,
                cacheKey = device.screenshotCacheKey(),
                maxWidthPx = 512,
                maxHeightPx = 300,
            )
        when {
            source.isNullOrBlank() ->
                showSnapshotMessage(context.getString(R.string.widget_no_screenshot), showInfo = true)
            bitmap == null ->
                showSnapshotMessage(context.getString(R.string.widget_screenshot_cache_waiting), showInfo = true)
            else -> {
                setImageViewBitmap(R.id.snapshot_widget_image, bitmap)
                setViewVisibility(R.id.snapshot_widget_image, View.VISIBLE)
                setViewVisibility(R.id.snapshot_widget_message, View.GONE)
                setViewVisibility(R.id.snapshot_widget_info, View.VISIBLE)
            }
        }
        return bitmap != null
    }

    private fun selectSnapshotDevices(
        cache: WidgetCacheSnapshot,
        settings: WidgetSettings,
    ): SnapshotDeviceSelection {
        val feed = cache.feed
        val user =
            feed?.users?.firstOrNull { it.userId == settings.targetUserId }
                ?: feed?.users?.firstOrNull()
        val screenshotDevices = screenshotDeviceSequence(user?.devices.orEmpty(), settings.targetDeviceId)
        val devices =
            screenshotDevices.ifEmpty {
                listOfNotNull(
                    user?.devices?.firstOrNull { it.deviceId == settings.targetDeviceId }
                        ?: user?.devices?.firstOrNull(),
                )
            }
        return SnapshotDeviceSelection(user, devices)
    }

    private fun RemoteViews.renderSnapshotDevice(
        context: Context,
        device: WidgetDeviceStatus,
        settings: WidgetSettings,
        palette: WidgetPalette,
    ) {
        if (settings.showIcons) {
            setViewVisibility(R.id.snapshot_widget_app_icon, View.VISIBLE)
            val targetSize = (34 * context.resources.displayMetrics.density).toInt().coerceAtLeast(34)
            val icon = WidgetImageCache.readScaled(context, device.appIconUrl, targetSize)
            if (icon == null) {
                setImageViewResource(R.id.snapshot_widget_app_icon, R.drawable.ic_neko_monochrome)
            } else {
                setImageViewBitmap(R.id.snapshot_widget_app_icon, icon)
            }
        } else {
            setViewVisibility(R.id.snapshot_widget_app_icon, View.GONE)
        }
        setTextViewText(R.id.snapshot_widget_device, device.deviceName)
        setTextViewText(
            R.id.snapshot_widget_app,
            device.appName.takeIf(String::isNotBlank) ?: context.getString(R.string.widget_no_activity),
        )
        if (settings.showMusic && !device.media?.title.isNullOrBlank()) {
            setTextViewText(R.id.snapshot_widget_media, device.media?.title)
            setViewVisibility(R.id.snapshot_widget_media, View.VISIBLE)
        } else {
            setViewVisibility(R.id.snapshot_widget_media, View.GONE)
        }
        setTextViewText(R.id.snapshot_widget_status, context.getString(device.statusLabel()))
        setTextColor(R.id.snapshot_widget_status, device.statusColor(palette))
        setTextViewCompoundDrawables(R.id.snapshot_widget_status, device.statusDot(), 0, 0, 0)
        val batteryColor = device.batteryColor(palette)
        setImageViewResource(R.id.snapshot_widget_battery_icon, device.batteryIcon())
        setInt(R.id.snapshot_widget_battery_icon, "setColorFilter", batteryColor)
        setTextViewText(R.id.snapshot_widget_battery_text, "${device.batteryLevel}%")
        setTextColor(R.id.snapshot_widget_battery_text, batteryColor)
    }

    private fun RemoteViews.showSnapshotMessage(
        message: String,
        showInfo: Boolean,
    ) {
        setTextViewText(R.id.snapshot_widget_message, message)
        setViewVisibility(R.id.snapshot_widget_message, View.VISIBLE)
        setViewVisibility(R.id.snapshot_widget_image, View.GONE)
        setViewVisibility(R.id.snapshot_widget_stale, View.GONE)
        setViewVisibility(R.id.snapshot_widget_info, if (showInfo) View.VISIBLE else View.GONE)
    }

    private fun RemoteViews.setSnapshotRefreshFeedback(refreshing: Boolean) {
        setViewVisibility(R.id.snapshot_widget_refresh, if (refreshing) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.snapshot_widget_refresh_progress, if (refreshing) View.VISIBLE else View.GONE)
    }

    private fun RemoteViews.applySnapshotPalette(
        palette: WidgetPalette,
        opacityPercent: Int,
    ) {
        setImageViewResource(R.id.snapshot_widget_background, palette.backgroundDrawable)
        setInt(R.id.snapshot_widget_background, "setImageAlpha", opacityPercent.coerceIn(50, 100) * 255 / 100)
        setTextColor(R.id.snapshot_widget_title, palette.onSurface)
        setTextColor(R.id.snapshot_widget_updated, palette.onSurfaceVariant)
        setTextColor(R.id.snapshot_widget_message, palette.onSurfaceVariant)
        setTextColor(R.id.snapshot_widget_device, palette.onSurface)
        setTextColor(R.id.snapshot_widget_app, palette.onSurfaceVariant)
        setTextColor(R.id.snapshot_widget_media, palette.onSurfaceVariant)
    }

    private fun WidgetDeviceStatus.snapshotUpdatedLabel(
        context: Context,
        cache: WidgetCacheSnapshot,
    ): String {
        val screenshotTime = screenshotUpdatedAt?.formattedWidgetTime()
        if (screenshotTime != null) return context.getString(R.string.widget_captured_at, screenshotTime)
        val timestamp = cache.feed?.fetchedAtEpochMs ?: cache.lastAttemptEpochMs
        val formatted =
            timestamp?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                ?: context.getString(R.string.widget_never)
        return context.getString(if (cache.errorCode == null) R.string.widget_updated_at else R.string.widget_cached_at, formatted)
    }

    private fun String.formattedWidgetTime(): String? =
        runCatching {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(this))
        }.getOrNull()

    private fun snapshotOpenAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun snapshotRefreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, NekoSnapshotWidgetReceiver::class.java).setAction(NekoSnapshotWidgetReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun snapshotSwitchIntent(
        context: Context,
        appWidgetId: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            200_000 + appWidgetId,
            Intent(context, NekoSnapshotWidgetReceiver::class.java)
                .setAction(NekoSnapshotWidgetReceiver.ACTION_SWITCH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

private data class SnapshotTarget(
    val user: WidgetUserStatus?,
    val device: WidgetDeviceStatus?,
)

private data class SnapshotDeviceSelection(
    val user: WidgetUserStatus?,
    val devices: List<WidgetDeviceStatus>,
)

private fun WidgetDeviceStatus.screenshotCacheKey(): String {
    val source = screenshotThumbnailUrl ?: screenshotUrl.orEmpty()
    return "screenshot|$deviceId|${screenshotUpdatedAt.orEmpty()}|$source"
}

class NekoWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        NekoWidgetRenderer.update(context, appWidgetManager, appWidgetIds)
        WidgetRefreshScheduler.refreshNow(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_REFRESH -> {
                WidgetRefreshScheduler.refreshNow(context)
                return
            }
            ACTION_SWITCH -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val validIds =
                    AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, NekoWidgetReceiver::class.java))
                if (appWidgetId in validIds) NekoWidgetRenderer.advance(context, appWidgetId)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        NekoWidgetRenderer.clearPages(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        const val ACTION_REFRESH = "com.nekonf.nekostatus.action.REFRESH_WIDGET"
        const val ACTION_SWITCH = "com.nekonf.nekostatus.action.SWITCH_WIDGET_DEVICE"
    }
}

class NekoSnapshotWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        NekoSnapshotWidgetRenderer.update(context, appWidgetManager, appWidgetIds)
        WidgetRefreshScheduler.refreshNow(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_REFRESH -> {
                WidgetRefreshScheduler.refreshNow(context)
                return
            }
            ACTION_SWITCH -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val validIds =
                    AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, NekoSnapshotWidgetReceiver::class.java))
                if (appWidgetId in validIds) NekoSnapshotWidgetRenderer.advance(context, appWidgetId)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        NekoSnapshotWidgetRenderer.clearPages(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        const val ACTION_REFRESH = "com.nekonf.nekostatus.action.REFRESH_SNAPSHOT_WIDGET"
        const val ACTION_SWITCH = "com.nekonf.nekostatus.action.SWITCH_SNAPSHOT_WIDGET_DEVICE"
    }
}
