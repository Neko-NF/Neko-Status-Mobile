package com.nekonf.nekostatus.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `redacts bearer and named secrets in plain and JSON messages`() {
        val input =
            "Bearer abc.def token=plain-secret " +
                "{\"deviceKey\":\"json-secret\",\"auth_token\":\"stored-secret\"}"

        val redacted = redactDiagnosticMessage(input)

        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("plain-secret"))
        assertFalse(redacted.contains("json-secret"))
        assertFalse(redacted.contains("stored-secret"))
        assertEquals(4, Regex("\\[REDACTED]").findAll(redacted).count())
    }
}
