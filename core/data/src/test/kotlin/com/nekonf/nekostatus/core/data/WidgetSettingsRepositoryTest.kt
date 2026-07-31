package com.nekonf.nekostatus.core.data

import com.nekonf.nekostatus.core.model.WidgetDisplayMode
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetSettingsRepositoryTest {
    @Test
    fun `account reset clears account selections and preserves device presentation`() {
        val current =
            WidgetSettings(
                enabled = true,
                refreshIntervalMinutes = 45,
                displayMode = WidgetDisplayMode.SINGLE,
                targetUserId = "account-a-user",
                targetDeviceId = "account-a-screenshot",
                selectedDeviceIds = listOf("account-a-phone", "account-a-pc"),
                showDeviceSwitcher = false,
                theme = WidgetTheme.DARK,
                backgroundOpacityPercent = 70,
                showMusic = false,
                showIcons = false,
                showScreenshot = true,
            )

        val reset = current.withResetAccountScope()

        assertFalse(reset.enabled)
        assertEquals(WidgetDisplayMode.ALL, reset.displayMode)
        assertNull(reset.targetUserId)
        assertNull(reset.targetDeviceId)
        assertEquals(emptyList<String>(), reset.selectedDeviceIds)
        assertFalse(reset.showScreenshot)
        assertEquals(45, reset.refreshIntervalMinutes)
        assertFalse(reset.showDeviceSwitcher)
        assertEquals(WidgetTheme.DARK, reset.theme)
        assertEquals(70, reset.backgroundOpacityPercent)
        assertFalse(reset.showMusic)
        assertFalse(reset.showIcons)
    }
}
