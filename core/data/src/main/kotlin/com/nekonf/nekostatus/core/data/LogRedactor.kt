package com.nekonf.nekostatus.core.data

private val BEARER_VALUE = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
private val NAMED_SECRET =
    Regex("(?i)([\\\"']?(?:deviceKey|device_key|token|auth_token|apiKey)[\\\"']?\\s*[:=]\\s*)([\\\"']?)([^\\\"',\\s}]+)([\\\"']?)")

internal fun redactDiagnosticMessage(message: String): String =
    NAMED_SECRET.replace(BEARER_VALUE.replace(message, "Bearer [REDACTED]")) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]${match.groupValues[4]}"
    }
