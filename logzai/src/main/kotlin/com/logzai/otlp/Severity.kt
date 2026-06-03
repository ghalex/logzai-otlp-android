package com.logzai.otlp

import android.util.Log
import io.opentelemetry.api.logs.Severity

/**
 * Log levels exposed by the client, mapped to OTLP [Severity] numbers and logcat
 * priorities. The OTLP numbers match the Python/JS clients
 * (debug=5, info=9, warn=13, error=17).
 */
internal enum class Level(val severity: Severity, val logcatPriority: Int) {
    DEBUG(Severity.DEBUG, Log.DEBUG),
    INFO(Severity.INFO, Log.INFO),
    WARN(Severity.WARN, Log.WARN),
    ERROR(Severity.ERROR, Log.ERROR),
}
