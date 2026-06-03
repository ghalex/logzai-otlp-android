package com.logzai.otlp

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogzaiTest {

    @Test
    fun `endpoint building appends paths`() {
        assertEquals("https://ingest.logzai.com/traces", tracesUrl("https://ingest.logzai.com"))
        assertEquals("https://ingest.logzai.com/logs", logsUrl("https://ingest.logzai.com"))
    }

    @Test
    fun `endpoint building trims trailing slashes`() {
        assertEquals("https://x.io/traces", tracesUrl("https://x.io///"))
        assertEquals("https://x.io/logs", logsUrl("https://x.io/"))
    }

    @Test
    fun `severity numbers match the python and js clients`() {
        assertEquals(Severity.DEBUG, Level.DEBUG.severity)
        assertEquals(Severity.INFO, Level.INFO.severity)
        assertEquals(Severity.WARN, Level.WARN.severity)
        assertEquals(Severity.ERROR, Level.ERROR.severity)
        assertEquals(5, Level.DEBUG.severity.severityNumber)
        assertEquals(9, Level.INFO.severity.severityNumber)
        assertEquals(13, Level.WARN.severity.severityNumber)
        assertEquals(17, Level.ERROR.severity.severityNumber)
    }

    @Test
    fun `options expose the documented defaults`() {
        val o = LogzaiOptions(ingestToken = "t")
        assertEquals("https://ingest.logzai.com", o.ingestEndpoint)
        assertEquals("app", o.serviceName)
        assertEquals("default", o.serviceNamespace)
        assertEquals("prod", o.environment)
        assertEquals(false, o.mirrorToConsole)
        assertEquals(10_000L, o.timeoutMillis)
    }

    @Test
    fun `attribute conversion preserves types`() {
        val attrs = mapOf(
            "s" to "str",
            "b" to true,
            "i" to 42,
            "l" to 7L,
            "d" to 1.5,
            "f" to 2.5f,
            "other" to listOf(1, 2),
        ).toAttributes()

        assertEquals("str", attrs.get(AttributeKey.stringKey("s")))
        assertEquals(true, attrs.get(AttributeKey.booleanKey("b")))
        assertEquals(42L, attrs.get(AttributeKey.longKey("i")))
        assertEquals(7L, attrs.get(AttributeKey.longKey("l")))
        assertEquals(1.5, attrs.get(AttributeKey.doubleKey("d"))!!, 0.0)
        assertEquals(2.5, attrs.get(AttributeKey.doubleKey("f"))!!, 0.0)
        assertEquals("[1, 2]", attrs.get(AttributeKey.stringKey("other")))
        // Int stored as long, so the string key must not also resolve.
        assertNull(attrs.get(AttributeKey.stringKey("i")))
    }
}
