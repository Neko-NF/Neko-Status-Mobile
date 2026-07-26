package com.nekonf.nekostatus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSettingsTest {
    @Test
    fun `defaults use official repository and conservative automatic download`() {
        val settings = UpdateSettings()

        assertTrue(settings.automaticChecks)
        assertFalse(settings.automaticDownload)
        assertEquals(UpdateSource.OFFICIAL, settings.source)
        assertEquals(UpdateSettings.OFFICIAL_REPOSITORY, settings.activeRepository)
    }

    @Test
    fun `normalizes supported GitHub repository input forms`() {
        assertEquals("Neko-NF/Neko-Status-Mobile", normalizeGitHubRepository(" Neko-NF/Neko-Status-Mobile "))
        assertEquals(
            "Neko-NF/Neko-Status-Mobile",
            normalizeGitHubRepository("https://github.com/Neko-NF/Neko-Status-Mobile.git/"),
        )
        assertEquals(
            "Neko-NF/Neko-Status-Mobile",
            normalizeGitHubRepository("github.com/Neko-NF/Neko-Status-Mobile"),
        )
    }

    @Test
    fun `rejects non GitHub or malformed repository input`() {
        val invalidValues =
            listOf(
                "",
                "owner",
                "owner/repository/releases",
                "-owner/repository",
                "owner--name/repository",
                "owner/repository name",
                "http://github.com/owner/repository",
                "https://example.com/owner/repository",
                "https://github.com/owner/repository?ref=main",
                "git@github.com:owner/repository.git",
            )

        invalidValues.forEach { value ->
            assertNull(value, normalizeGitHubRepository(value))
            assertFalse(value, isValidGitHubRepository(value))
        }
    }

    @Test
    fun `custom settings are normalized and invalid custom source is rejected`() {
        val normalized =
            UpdateSettings(
                source = UpdateSource.CUSTOM,
                customRepository = "https://github.com/Example/Updates.git",
            ).normalized()

        assertEquals("Example/Updates", normalized.customRepository)
        assertEquals("Example/Updates", normalized.activeRepository)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateSettings(
                source = UpdateSource.CUSTOM,
                customRepository = "https://example.com/not/github",
            ).normalized()
        }
    }
}
