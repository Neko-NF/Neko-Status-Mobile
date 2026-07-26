package com.nekonf.nekostatus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test
    fun retryBackoffIsBoundedAndHonorsRetryAfter() {
        assertEquals(1L, RetryPolicy.delaySeconds(1))
        assertEquals(16L, RetryPolicy.delaySeconds(5))
        assertEquals(300L, RetryPolicy.delaySeconds(99))
        assertEquals(120L, RetryPolicy.delaySeconds(2, retryAfterSeconds = 120))
    }

    @Test
    fun releaseSortsAfterPrerelease() {
        assertTrue(VersionComparator.isNewer("2.0.0-alpha.1", "2.0.0"))
        assertTrue(VersionComparator.isNewer("1.9.9", "2.0.0-alpha.1"))
        assertFalse(VersionComparator.isNewer("2.0.0", "2.0.0-rc.1"))
        assertEquals(0, VersionComparator.compare("v2.0", "2.0.0"))
    }
}
