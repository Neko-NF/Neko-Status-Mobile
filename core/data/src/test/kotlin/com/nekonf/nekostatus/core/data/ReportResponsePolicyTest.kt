package com.nekonf.nekostatus.core.data

import com.nekonf.nekostatus.core.model.ReportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReportResponsePolicyTest {
    @Test
    fun `credential failures are terminal`() {
        listOf(401, 403, 404).forEach { status ->
            val outcome = ReportResponsePolicy.failure(status, "INVALID_KEY", null, null)
            assertTrue(outcome is ReportOutcome.Terminal)
            assertEquals("INVALID_KEY", (outcome as ReportOutcome.Terminal).code)
        }
    }

    @Test
    fun `rate limit supports seconds and HTTP date retry after`() {
        val now = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        val seconds = ReportResponsePolicy.failure(429, null, null, "45", now)
        val date = ReportResponsePolicy.failure(429, null, null, "Sun, 26 Jul 2026 12:02:00 GMT", now)

        assertEquals(45L, (seconds as ReportOutcome.Retryable).retryAfterSeconds)
        assertEquals(120L, (date as ReportOutcome.Retryable).retryAfterSeconds)
    }

    @Test
    fun `server failures retry and rejected client requests terminate`() {
        assertTrue(ReportResponsePolicy.failure(503, null, "maintenance", null) is ReportOutcome.Retryable)
        assertTrue(ReportResponsePolicy.failure(422, "INVALID_PAYLOAD", null, null) is ReportOutcome.Terminal)
        assertNull(ReportResponsePolicy.parseRetryAfter("not-a-date"))
    }
}
