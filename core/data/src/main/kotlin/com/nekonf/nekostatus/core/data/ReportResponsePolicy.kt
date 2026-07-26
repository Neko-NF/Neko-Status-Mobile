package com.nekonf.nekostatus.core.data

import com.nekonf.nekostatus.core.model.ReportOutcome
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal object ReportResponsePolicy {
    fun failure(
        httpCode: Int,
        serverCode: String?,
        serverMessage: String?,
        retryAfterHeader: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ReportOutcome {
        val code = serverCode ?: "HTTP_$httpCode"
        return when (httpCode) {
            401, 403, 404 -> ReportOutcome.Terminal(code, serverMessage ?: "设备凭据失效")
            429 ->
                ReportOutcome.Retryable(
                    code = serverCode ?: "RATE_LIMITED",
                    message = serverMessage ?: "请求过于频繁",
                    retryAfterSeconds = parseRetryAfter(retryAfterHeader, nowEpochMs),
                )
            in 500..599 -> ReportOutcome.Retryable(code, serverMessage ?: "服务器暂时不可用")
            else -> ReportOutcome.Terminal(code, serverMessage ?: "上报请求被拒绝")
        }
    }

    fun parseRetryAfter(
        header: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Long? {
        val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val seconds = value.toLongOrNull()
        if (seconds != null) return seconds.coerceAtLeast(0L)
        val retryAt =
            runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        return retryAt?.let { ceil((it - nowEpochMs).coerceAtLeast(0L) / 1_000.0).toLong() }
    }
}
