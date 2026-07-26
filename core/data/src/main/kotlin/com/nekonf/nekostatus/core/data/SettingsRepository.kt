package com.nekonf.nekostatus.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nekonf.nekostatus.core.model.ReportingSettings
import com.nekonf.nekostatus.core.model.ServerCapabilities
import com.nekonf.nekostatus.core.model.ServerConfig
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateSource
import com.nekonf.nekostatus.core.model.normalizeGitHubRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        private object Keys {
            val productionUrl = stringPreferencesKey("production_url")
            val localUrl = stringPreferencesKey("local_url")
            val useLocal = booleanPreferencesKey("use_local_server")
            val reportingEnabled = booleanPreferencesKey("reporting_enabled")
            val restoreAfterBoot = booleanPreferencesKey("restore_after_boot")
            val intervalSeconds = intPreferencesKey("report_interval_seconds")
            val enhancedDetection = booleanPreferencesKey("enhanced_app_detection")
            val includeMedia = booleanPreferencesKey("include_media")
            val themeMode = stringPreferencesKey("theme_mode")
            val dynamicColor = booleanPreferencesKey("dynamic_color")
            val onboardingComplete = booleanPreferencesKey("onboarding_complete")
            val capabilitiesHistory = booleanPreferencesKey("capabilities_history")
            val capabilitiesAnnouncements = booleanPreferencesKey("capabilities_announcements")
            val capabilitiesActivity = booleanPreferencesKey("capabilities_activity")
            val capabilitiesMultiDeviceWidget = booleanPreferencesKey("capabilities_multi_device_widget")
            val capabilitiesCheckedAt = longPreferencesKey("capabilities_checked_at")
            val automaticUpdateChecks = booleanPreferencesKey("automatic_update_checks")
            val automaticUpdateDownload = booleanPreferencesKey("automatic_update_download")
            val updateSource = stringPreferencesKey("update_source")
            val customUpdateRepository = stringPreferencesKey("custom_update_repository")
        }

        val serverConfig: Flow<ServerConfig> =
            combine(
                dataStore.data.map { it[Keys.productionUrl] ?: ServerConfig.DEFAULT_PRODUCTION_URL },
                dataStore.data.map { it[Keys.localUrl] ?: ServerConfig.DEFAULT_LOCAL_URL },
                dataStore.data.map { it[Keys.useLocal] ?: false },
            ) { productionUrl, localUrl, useLocal ->
                ServerConfig(productionUrl, localUrl, useLocal)
            }.distinctUntilChanged()

        val reportingSettings: Flow<ReportingSettings> =
            combine(
                dataStore.data.map { it[Keys.reportingEnabled] ?: false },
                dataStore.data.map { it[Keys.restoreAfterBoot] ?: false },
                dataStore.data.map { (it[Keys.intervalSeconds] ?: 10).coerceIn(10, 300) },
                dataStore.data.map { it[Keys.enhancedDetection] ?: false },
                dataStore.data.map { it[Keys.includeMedia] ?: true },
            ) { enabled, restore, interval, enhanced, includeMedia ->
                ReportingSettings(enabled, restore, interval, enhanced, includeMedia)
            }.distinctUntilChanged()

        val themeMode: Flow<String> = dataStore.data.map { it[Keys.themeMode] ?: "system" }.distinctUntilChanged()
        val dynamicColor: Flow<Boolean> = dataStore.data.map { it[Keys.dynamicColor] ?: false }.distinctUntilChanged()
        val onboardingComplete: Flow<Boolean> = dataStore.data.map { it[Keys.onboardingComplete] ?: false }.distinctUntilChanged()

        val updateSettings: Flow<UpdateSettings> =
            dataStore.data
                .map { preferences ->
                    val customRepository =
                        normalizeGitHubRepository(preferences[Keys.customUpdateRepository].orEmpty()).orEmpty()
                    val storedSource =
                        preferences[Keys.updateSource]
                            ?.let { value -> runCatching { UpdateSource.valueOf(value) }.getOrNull() }
                            ?: UpdateSource.OFFICIAL
                    val source =
                        storedSource.takeUnless {
                            it == UpdateSource.CUSTOM && customRepository.isEmpty()
                        } ?: UpdateSource.OFFICIAL
                    UpdateSettings(
                        automaticChecks = preferences[Keys.automaticUpdateChecks] ?: true,
                        automaticDownload = preferences[Keys.automaticUpdateDownload] ?: false,
                        source = source,
                        customRepository = customRepository,
                    )
                }.distinctUntilChanged()

        val serverCapabilities: Flow<ServerCapabilities> =
            combine(
                dataStore.data.map { it[Keys.capabilitiesHistory] ?: false },
                dataStore.data.map { it[Keys.capabilitiesAnnouncements] ?: false },
                dataStore.data.map { it[Keys.capabilitiesActivity] ?: false },
                dataStore.data.map { it[Keys.capabilitiesMultiDeviceWidget] ?: false },
                dataStore.data.map { it[Keys.capabilitiesCheckedAt] ?: 0L },
            ) { history, announcements, activity, multiDeviceWidget, checkedAt ->
                ServerCapabilities(
                    history = history,
                    announcements = announcements,
                    activity = activity,
                    multiDeviceWidget = multiDeviceWidget,
                    checkedAtEpochMs = checkedAt.takeIf { it > 0L },
                )
            }.distinctUntilChanged()

        suspend fun updateServer(config: ServerConfig) {
            dataStore.edit {
                it[Keys.productionUrl] = config.productionUrl.trimEnd('/')
                it[Keys.localUrl] = config.localUrl.trimEnd('/')
                it[Keys.useLocal] = config.useLocalServer
            }
        }

        suspend fun updateReporting(settings: ReportingSettings) {
            dataStore.edit {
                it[Keys.reportingEnabled] = settings.enabled
                it[Keys.restoreAfterBoot] = settings.restoreAfterBoot
                it[Keys.intervalSeconds] = settings.intervalSeconds.coerceIn(10, 300)
                it[Keys.enhancedDetection] = settings.enhancedAppDetection
                it[Keys.includeMedia] = settings.includeMedia
            }
        }

        suspend fun setTheme(
            mode: String,
            useDynamicColor: Boolean,
        ) {
            dataStore.edit {
                it[Keys.themeMode] = mode
                it[Keys.dynamicColor] = useDynamicColor
            }
        }

        suspend fun completeOnboarding() {
            dataStore.edit { it[Keys.onboardingComplete] = true }
        }

        suspend fun updateUpdateSettings(settings: UpdateSettings) {
            val normalized = settings.normalized()
            dataStore.edit {
                it[Keys.automaticUpdateChecks] = normalized.automaticChecks
                it[Keys.automaticUpdateDownload] = normalized.automaticDownload
                it[Keys.updateSource] = normalized.source.name
                it[Keys.customUpdateRepository] = normalized.customRepository
            }
        }

        suspend fun setAutomaticUpdateChecks(enabled: Boolean) {
            dataStore.edit { it[Keys.automaticUpdateChecks] = enabled }
        }

        suspend fun setAutomaticUpdateDownload(enabled: Boolean) {
            dataStore.edit { it[Keys.automaticUpdateDownload] = enabled }
        }

        suspend fun setUpdateRepository(
            source: UpdateSource,
            customRepository: String,
        ) {
            val normalized =
                UpdateSettings(
                    source = source,
                    customRepository = customRepository,
                ).normalized()
            dataStore.edit {
                it[Keys.updateSource] = normalized.source.name
                it[Keys.customUpdateRepository] = normalized.customRepository
            }
        }

        suspend fun updateServerCapabilities(capabilities: ServerCapabilities) {
            dataStore.edit {
                it[Keys.capabilitiesHistory] = capabilities.history
                it[Keys.capabilitiesAnnouncements] = capabilities.announcements
                it[Keys.capabilitiesActivity] = capabilities.activity
                it[Keys.capabilitiesMultiDeviceWidget] = capabilities.multiDeviceWidget
                it[Keys.capabilitiesCheckedAt] = capabilities.checkedAtEpochMs ?: System.currentTimeMillis()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("neko-settings.preferences_pb") }
}
