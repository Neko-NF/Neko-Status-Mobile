package com.nekonf.nekostatus.core.data

import android.content.Context
import com.nekonf.nekostatus.core.model.WidgetDisplayMode
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.WidgetTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

object WidgetSettingsStore {
    private const val PREFERENCES = "neko-widget-settings"
    private const val ENABLED = "enabled"
    private const val REFRESH_INTERVAL = "refresh_interval"
    private const val DISPLAY_MODE = "display_mode"
    private const val TARGET_USER_ID = "target_user_id"
    private const val TARGET_DEVICE_ID = "target_device_id"
    private const val THEME = "theme"
    private const val BACKGROUND_OPACITY = "background_opacity"
    private const val SHOW_MUSIC = "show_music"
    private const val SHOW_ICONS = "show_icons"
    private const val SHOW_SCREENSHOT = "show_screenshot"

    fun read(context: Context): WidgetSettings {
        val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return WidgetSettings(
            enabled = values.getBoolean(ENABLED, false),
            refreshIntervalMinutes = values.getInt(REFRESH_INTERVAL, 15).coerceIn(15, 180),
            displayMode = values.enumValue(DISPLAY_MODE, WidgetDisplayMode.ALL),
            targetUserId = values.getString(TARGET_USER_ID, null),
            targetDeviceId = values.getString(TARGET_DEVICE_ID, null),
            theme = values.enumValue(THEME, WidgetTheme.SYSTEM),
            backgroundOpacityPercent = values.getInt(BACKGROUND_OPACITY, 90).coerceIn(50, 100),
            showMusic = values.getBoolean(SHOW_MUSIC, true),
            showIcons = values.getBoolean(SHOW_ICONS, true),
            showScreenshot = values.getBoolean(SHOW_SCREENSHOT, false),
        )
    }

    fun write(
        context: Context,
        settings: WidgetSettings,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, settings.enabled)
            .putInt(REFRESH_INTERVAL, settings.refreshIntervalMinutes.coerceIn(15, 180))
            .putString(DISPLAY_MODE, settings.displayMode.name)
            .putString(TARGET_USER_ID, settings.targetUserId)
            .putString(TARGET_DEVICE_ID, settings.targetDeviceId)
            .putString(THEME, settings.theme.name)
            .putInt(BACKGROUND_OPACITY, settings.backgroundOpacityPercent.coerceIn(50, 100))
            .putBoolean(SHOW_MUSIC, settings.showMusic)
            .putBoolean(SHOW_ICONS, settings.showIcons)
            .putBoolean(SHOW_SCREENSHOT, settings.showScreenshot)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
        key: String,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(getString(key, null).orEmpty()) }.getOrDefault(fallback)
}

@Singleton
class WidgetSettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val _settings = MutableStateFlow(WidgetSettingsStore.read(context))
        val settings: StateFlow<WidgetSettings> = _settings.asStateFlow()

        fun update(settings: WidgetSettings) {
            WidgetSettingsStore.write(context, settings)
            _settings.value = settings
        }
    }
