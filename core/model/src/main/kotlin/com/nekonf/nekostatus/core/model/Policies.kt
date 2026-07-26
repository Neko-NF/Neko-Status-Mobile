package com.nekonf.nekostatus.core.model

import kotlin.math.min

object RetryPolicy {
    fun delaySeconds(
        consecutiveFailures: Int,
        retryAfterSeconds: Long? = null,
        jitterFraction: Double = 0.0,
    ): Long {
        val failures = consecutiveFailures.coerceAtLeast(1)
        val base = min(300L, 1L shl min(failures - 1, 9))
        val jitter = (base * jitterFraction.coerceIn(0.0, 0.25)).toLong()
        return maxOf(retryAfterSeconds ?: 0, base + jitter)
    }
}

object VersionComparator {
    fun compare(
        left: String,
        right: String,
    ): Int {
        val a = ParsedVersion.parse(left)
        val b = ParsedVersion.parse(right)
        return compareNumbers(a.numbers, b.numbers).takeIf { it != 0 }
            ?: comparePreRelease(a.preRelease, b.preRelease)
    }

    fun isNewer(
        current: String,
        candidate: String,
    ): Boolean = compare(current, candidate) < 0

    private fun compareNumbers(
        left: List<Int>,
        right: List<Int>,
    ): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val result = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun comparePreRelease(
        left: String?,
        right: String?,
    ): Int =
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            else -> comparePreReleaseParts(left.split('.'), right.split('.'))
        }

    private fun comparePreReleaseParts(
        leftParts: List<String>,
        rightParts: List<String>,
    ): Int {
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val l = leftParts.getOrNull(index)
            val r = rightParts.getOrNull(index)
            val comparison = comparePreReleasePart(l, r)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun comparePreReleasePart(
        left: String?,
        right: String?,
    ): Int =
        when {
            left == null -> -1
            right == null -> 1
            left.toIntOrNull() != null && right.toIntOrNull() != null -> left.toInt().compareTo(right.toInt())
            left.toIntOrNull() != null -> -1
            right.toIntOrNull() != null -> 1
            else -> left.compareTo(right, ignoreCase = true)
        }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: String?) {
        companion object {
            fun parse(raw: String): ParsedVersion {
                val normalized = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
                val parts = normalized.split('-', limit = 2)
                val numbers = parts.first().split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
                return ParsedVersion(numbers, parts.getOrNull(1)?.takeIf(String::isNotBlank))
            }
        }
    }
}
