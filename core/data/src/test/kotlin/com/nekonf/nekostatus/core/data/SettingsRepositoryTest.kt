package com.nekonf.nekostatus.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nekonf.nekostatus.core.model.ReportingSettings
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `update settings defaults are stable`() =
        runTest {
            val repository = createRepository()

            assertEquals(UpdateSettings(), repository.updateSettings.first())
        }

    @Test
    fun `keep alive reminders default off and persist supported interval`() =
        runTest {
            val dataStore = createDataStore()
            val repository = SettingsRepository(dataStore)

            assertFalse(repository.reportingSettings.first().keepAliveReminderEnabled)
            assertEquals(6, repository.reportingSettings.first().keepAliveReminderIntervalHours)

            repository.updateReporting(
                ReportingSettings(
                    enabled = true,
                    keepAliveReminderEnabled = true,
                    keepAliveReminderIntervalHours = 48,
                ),
            )
            val restored = SettingsRepository(dataStore).reportingSettings.first()

            assertTrue(restored.keepAliveReminderEnabled)
            assertEquals(48, restored.keepAliveReminderIntervalHours)
        }

    @Test
    fun `custom update settings are normalized and survive repository recreation`() =
        runTest {
            val dataStore = createDataStore()
            val repository = SettingsRepository(dataStore)
            repository.updateUpdateSettings(
                UpdateSettings(
                    automaticChecks = false,
                    automaticDownload = true,
                    source = UpdateSource.CUSTOM,
                    customRepository = " https://github.com/Example/Updates.git ",
                ),
            )

            val restored = SettingsRepository(dataStore).updateSettings.first()

            assertFalse(restored.automaticChecks)
            assertTrue(restored.automaticDownload)
            assertEquals(UpdateSource.CUSTOM, restored.source)
            assertEquals("Example/Updates", restored.customRepository)
            assertEquals("Example/Updates", restored.activeRepository)
        }

    @Test
    fun `individual update controls persist independently`() =
        runTest {
            val sourceRepository = createRepository()
            sourceRepository.setUpdateRepository(UpdateSource.CUSTOM, "Neko-NF/Neko-Status-Mobile")
            val sourceSettings = sourceRepository.updateSettings.first()
            assertEquals(UpdateSource.CUSTOM, sourceSettings.source)
            assertEquals("Neko-NF/Neko-Status-Mobile", sourceSettings.customRepository)
            assertTrue(sourceSettings.automaticChecks)
            assertFalse(sourceSettings.automaticDownload)

            val checksRepository = createRepository()
            checksRepository.setAutomaticUpdateChecks(false)
            val checksSettings = checksRepository.updateSettings.first()
            assertFalse(checksSettings.automaticChecks)
            assertFalse(checksSettings.automaticDownload)
            assertEquals(UpdateSource.OFFICIAL, checksSettings.source)

            val downloadRepository = createRepository()
            downloadRepository.setAutomaticUpdateDownload(true)
            val downloadSettings = downloadRepository.updateSettings.first()
            assertTrue(downloadSettings.automaticChecks)
            assertTrue(downloadSettings.automaticDownload)
            assertEquals(UpdateSource.OFFICIAL, downloadSettings.source)
        }

    @Test
    fun `invalid custom repository is rejected without changing persisted source`() =
        runTest {
            val repository = createRepository()

            val failure =
                runCatching {
                    repository.setUpdateRepository(UpdateSource.CUSTOM, "https://example.com/owner/repository")
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(UpdateSettings(), repository.updateSettings.first())
        }

    private fun TestScope.createRepository(): SettingsRepository = SettingsRepository(createDataStore())

    private fun TestScope.createDataStore() =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(temporaryFolder.newFolder(), "settings.preferences_pb") },
        )
}
