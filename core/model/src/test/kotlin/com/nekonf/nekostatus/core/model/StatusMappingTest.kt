package com.nekonf.nekostatus.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusMappingTest {
    @Test
    fun `screen state maps to stable wire presence`() {
        assertEquals(PresenceState.ONLINE, ScreenState.ON.toPresenceState())
        assertEquals(PresenceState.AWAY, ScreenState.LOCKED.toPresenceState())
        assertEquals(PresenceState.OFFLINE, ScreenState.OFF.toPresenceState())
    }

    @Test
    fun `server url removes trailing slash`() {
        assertEquals(
            "https://example.test",
            ServerConfig(productionUrl = "https://example.test/").activeUrl,
        )
    }
}
