package com.nekonf.nekostatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppResolutionTest {
    @Test
    fun `internal package falls back to nearby resolvable host application`() {
        val resolved =
            resolveForegroundApp(
                candidates =
                    listOf(
                        ForegroundAppCandidate("com.quark.scanking", 10_000),
                        ForegroundAppCandidate("com.quark.browser", 9_500),
                    ),
                previous = null,
                labelResolver = { if (it == "com.quark.browser") "夸克" else null },
            )

        assertEquals(ResolvedForegroundApp("com.quark.browser", "夸克"), resolved)
    }

    @Test
    fun `raw package name is never accepted as an application label`() {
        val resolved =
            resolveForegroundApp(
                candidates = listOf(ForegroundAppCandidate("com.example.hidden", 10_000)),
                previous = null,
                labelResolver = { it },
            )

        assertNull(resolved)
    }

    @Test
    fun `unrelated stale application is not used for a newer unresolved package`() {
        val resolved =
            resolveForegroundApp(
                candidates =
                    listOf(
                        ForegroundAppCandidate("com.example.internal", 100_000),
                        ForegroundAppCandidate("com.example.previous", 60_000),
                    ),
                previous = ResolvedForegroundApp("com.example.previous", "Previous"),
                labelResolver = { if (it == "com.example.previous") "Previous" else null },
            )

        assertNull(resolved)
    }

    @Test
    fun `previous resolved application is retained when no new foreground event exists`() {
        val previous = ResolvedForegroundApp("com.example.reader", "Reader")

        assertEquals(previous, resolveForegroundApp(emptyList(), previous) { null })
    }
}
