package com.logzai.otlp

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level as JulLevel
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * LIVE smoke test that talks to the real LogzAI ingest endpoint — it sends actual network
 * traffic and is NOT part of the normal test suite intent. Run it explicitly:
 *
 *   ./gradlew :logzai:test --tests "com.logzai.otlp.LiveSmokeTest"
 *
 * How "did it actually send?" is verified: the OpenTelemetry OTLP/HTTP exporter logs a
 * WARNING/SEVERE (via java.util.logging) whenever an export fails (e.g. a 401 from a bad
 * token) and is silent on success (HTTP 200). We capture those internal logs and:
 *   - [realTokenIsAccepted]  asserts NO export failure is logged (token accepted, 200).
 *   - [badTokenIsRejected]   asserts a failure IS logged (calibrates the detector — proves
 *                            that a real failure would not slip through as a false pass).
 *
 * Both tests print everything they capture so you can eyeball the exchange.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSmokeTest {

    private val captured = CopyOnWriteArrayList<LogRecord>()
    private lateinit var otel: Logger
    private lateinit var handler: Handler
    private var previousLevel: JulLevel? = null

    @Before
    fun installLogCapture() {
        captured.clear()
        otel = Logger.getLogger("io.opentelemetry")
        previousLevel = otel.level
        otel.level = JulLevel.ALL
        handler = object : Handler() {
            override fun publish(record: LogRecord) {
                captured.add(record)
                println("[otel ${record.level}] ${record.message}")
            }

            override fun flush() {}
            override fun close() {}
        }
        handler.level = JulLevel.ALL
        otel.addHandler(handler)
    }

    @After
    fun removeLogCapture() {
        otel.removeHandler(handler)
        otel.level = previousLevel
    }

    @Test
    fun realTokenIsAccepted() {
        assumeTrue(
            "Set LOGZAI_TOKEN to run this live test, e.g. " +
                "LOGZAI_TOKEN=… ./gradlew :logzai:testDebugUnitTest --tests '*LiveSmokeTest'",
            LIVE_TOKEN.isNotBlank(),
        )
        val logzai = LogzAI()
        logzai.init(
            RuntimeEnvironment.getApplication(),
            LogzaiOptions(
                ingestToken = LIVE_TOKEN,
                ingestEndpoint = LIVE_ENDPOINT,
                serviceName = "logzai-otlp-android-smoke",
                environment = "test",
                mirrorToConsole = true,
            ),
        )

        logzai.info(
            "hello from logzai-otlp-android smoke test (timestamped)",
            mapOf("source" to "LiveSmokeTest", "answer" to 42),
        )
        logzai.span("smoke-span", mapOf("kind" to "manual")) {
            logzai.info("log emitted inside a span (timestamped)")
        }
        logzai.error(
            "something went wrong in the smoke test (timestamped)",
            mapOf("source" to "LiveSmokeTest", "code" to 500),
        )
        logzai.exception(
            "caught an exception in the smoke test (timestamped)",
            IllegalStateException("boom from LiveSmokeTest"),
            mapOf("source" to "LiveSmokeTest"),
        )

        // shutdown() flushes the batch processors and joins, so by the time it returns the
        // HTTP POSTs have completed and any failure has been logged.
        logzai.shutdown()

        val failures = exportFailures()
        assertTrue(
            "Expected the real token to be accepted (HTTP 200, no export failures), " +
                "but the exporter logged: ${failures.map { it.message }}",
            failures.isEmpty(),
        )
        println("✓ real token accepted — no export failures logged")
    }

    @Test
    fun badTokenIsRejected() {
        val logzai = LogzAI()
        logzai.init(
            RuntimeEnvironment.getApplication(),
            LogzaiOptions(
                ingestToken = "obviously-invalid-token",
                ingestEndpoint = LIVE_ENDPOINT,
                serviceName = "logzai-otlp-android-smoke",
                mirrorToConsole = true,
            ),
        )
        logzai.info("this export should be rejected with 401")
        logzai.shutdown()

        val failures = exportFailures()
        assertTrue(
            "Detector calibration failed: a bad token should make the exporter log a failure, " +
                "but nothing failure-like was captured. If this is empty, realTokenIsAccepted " +
                "proves nothing. Captured: ${captured.map { "${it.level}:${it.message}" }}",
            failures.isNotEmpty(),
        )
        println("✓ bad token rejected — failure correctly detected: ${failures.first().message}")
    }

    /** Captured records that indicate an export did not succeed. */
    private fun exportFailures(): List<LogRecord> = captured.filter { record ->
        val msg = record.message?.lowercase().orEmpty()
        record.level.intValue() >= JulLevel.WARNING.intValue() &&
            ("export" in msg || "401" in msg || "failed" in msg || "status code" in msg)
    }

    private companion object {
        // Supplied at runtime so no secret lives in source. The real-token test skips
        // (org.junit.Assume) when LOGZAI_TOKEN is unset, so the normal suite stays offline.
        val LIVE_TOKEN: String = System.getenv("LOGZAI_TOKEN").orEmpty()
        val LIVE_ENDPOINT: String =
            System.getenv("LOGZAI_ENDPOINT") ?: "https://ingest.logzai.com"
    }
}
