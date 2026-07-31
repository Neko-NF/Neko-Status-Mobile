package com.nekonf.nekostatus

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAliveReminderTest {
    @Test
    fun `reminder interval is normalized to supported values`() {
        assertEquals(6, normalizeReminderInterval(1))
        assertEquals(12, normalizeReminderInterval(10))
        assertEquals(24, normalizeReminderInterval(25))
        assertEquals(48, normalizeReminderInterval(47))
    }
}
