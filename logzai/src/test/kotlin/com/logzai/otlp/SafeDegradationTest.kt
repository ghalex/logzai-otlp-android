package com.logzai.otlp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The library must never crash the host app because telemetry is misconfigured or unused.
 * These tests pin the "degrade to no-op" guarantee:
 *   - a malformed endpoint must not throw out of [LogzAI.init] (it would otherwise crash
 *     Application.onCreate());
 *   - and after a failed/absent init, every public call must run harmlessly — `span`/
 *     `spanSuspending` still execute the block (so the app's own work proceeds) and the log
 *     methods simply drop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeDegradationTest {

    @Test
    fun `init with a malformed endpoint does not throw and disables telemetry`() {
        val logzai = LogzAI()
        // A blank endpoint makes tracesUrl() = "/traces", which the OTLP exporter rejects as a
        // non-URL — exactly the kind of misconfig that used to crash at init().
        logzai.init(
            RuntimeEnvironment.getApplication(),
            LogzaiOptions(ingestToken = "", ingestEndpoint = ""),
        )

        // No exception above is the headline assertion. The rest proves the API stays usable.
        logzai.info("dropped, but no crash")
        logzai.error("also dropped")
        logzai.exception("dropped", IllegalStateException("boom"))
        logzai.shutdown()
    }

    @Test
    fun `span runs its block even when uninitialized`() {
        val logzai = LogzAI() // never init()'d
        var ran = false
        val result = logzai.span("noop-span", mapOf("k" to 1)) { span ->
            ran = true
            logzai.info("inside an uninitialized span")
            "ok"
        }
        assertEquals(true, ran)
        assertEquals("ok", result)
    }

    @Test
    fun `spanSuspending runs its block even when uninitialized`() = runBlocking {
        val logzai = LogzAI() // never init()'d
        var ran = false
        val result = logzai.spanSuspending("noop-span", mapOf("k" to 1)) { span ->
            ran = true
            logzai.info("inside an uninitialized suspend span")
            "ok"
        }
        assertEquals(true, ran)
        assertEquals("ok", result)
    }
}
