package com.nekonf.nekostatus.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountValidationTest {
    @Test
    fun `password change requires current password minimum length and matching confirmation`() {
        assertFalse(isValidPasswordChange("", "new-secret", "new-secret"))
        assertFalse(isValidPasswordChange("old-secret", "short", "short"))
        assertFalse(isValidPasswordChange("old-secret", "new-secret", "different"))
        assertTrue(isValidPasswordChange("old-secret", "new-secret", "new-secret"))
    }

    @Test
    fun `password validation does not trim password input`() {
        assertTrue(isValidPasswordChange(" old ", " 1234 ", " 1234 "))
        assertFalse(isValidPasswordChange("old", " 1234 ", "1234"))
    }

    @Test
    fun `requested switcher remains visible but disabled without another page`() {
        assertEquals(
            WidgetSwitcherPreviewState(visible = true, enabled = false),
            widgetSwitcherPreviewState(requested = true, applicable = true, pageCount = 1),
        )
        assertEquals(
            WidgetSwitcherPreviewState(visible = true, enabled = true),
            widgetSwitcherPreviewState(requested = true, applicable = true, pageCount = 2),
        )
        assertEquals(
            WidgetSwitcherPreviewState(visible = true, enabled = false),
            widgetSwitcherPreviewState(requested = true, applicable = false, pageCount = 3),
        )
    }
}
