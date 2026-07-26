package com.nekonf.nekostatus.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetResponsePolicyTest {
    @Test
    fun `authorization and unsupported responses are terminal`() {
        listOf(401, 403, 404, 501).forEach { status ->
            val failure = WidgetResponsePolicy.failure(status, "SERVER_CODE", "message", null)

            assertEquals("HTTP_$status", failure.code)
            assertTrue(failure.terminal)
        }
    }

    @Test
    fun `rate limit supports seconds and HTTP date retry after`() {
        val now = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        val seconds = WidgetResponsePolicy.failure(429, null, null, "45", now)
        val date = WidgetResponsePolicy.failure(429, null, null, "Sun, 26 Jul 2026 12:02:00 GMT", now)

        assertFalse(seconds.terminal)
        assertEquals(45L, seconds.retryAfterSeconds)
        assertEquals(120L, date.retryAfterSeconds)
    }

    @Test
    fun `server failures retry and rejected client requests terminate`() {
        assertFalse(WidgetResponsePolicy.failure(503, null, null, null).terminal)
        assertTrue(WidgetResponsePolicy.failure(422, "INVALID_PAYLOAD", null, null).terminal)
    }
}
