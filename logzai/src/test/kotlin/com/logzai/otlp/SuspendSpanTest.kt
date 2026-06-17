package com.logzai.otlp

import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the coroutine-aware [LogzAI.span] overload: a suspending span must propagate its
 * OpenTelemetry Context across a dispatcher hop, so that logs emitted *after* a suspension —
 * which resume on a different thread — still carry the span's `trace_id`/`span_id`.
 *
 * Unlike [ExportPipelineTest] this inspects the actual emitted records (via a capturing
 * [LogRecordProcessor] attached through the internal `init` test seam) rather than just the
 * propagated Context, so a regression in the record-stamping half of the requirement would
 * fail here too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspendSpanTest {

    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<LogRecordData>()

    /** Captures a snapshot of every emitted record so the test can read its span context. */
    private val capturing = object : LogRecordProcessor {
        override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
            records.add(logRecord.toLogRecordData())
        }

        override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        // The exporter treats any 200 as success; bodies are irrelevant to this test.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200)) }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `suspend span correlation survives a dispatcher hop`() = runBlocking {
        val logzai = LogzAI()
        logzai.init(
            RuntimeEnvironment.getApplication(),
            LogzaiOptions(
                ingestToken = "test-token",
                ingestEndpoint = server.url("/").toString(),
                serviceName = "logzai-otlp-android-suspend",
            ),
            extraLogProcessor = capturing,
        )

        var spanTraceId = ""
        var spanSpanId = ""

        logzai.spanSuspending("suspend-span", mapOf("kind" to "suspend")) { span ->
            spanTraceId = span.spanContext.traceId
            spanSpanId = span.spanContext.spanId

            withContext(Dispatchers.IO) {
                logzai.info("inside")
                // Force a real suspension + thread hop: the continuation resumes on a
                // (potentially) different IO thread, where the span is no longer the
                // makeCurrent() thread-local — only asContextElement() keeps it current.
                delay(50)
                logzai.info("after-suspend")
            }
        }

        logzai.shutdown()

        val inside = records.single { it.bodyValue?.asString() == "inside" }
        val afterSuspend = records.single { it.bodyValue?.asString() == "after-suspend" }

        assertTrue("span context should be valid", spanTraceId.isNotBlank())
        for ((label, record) in listOf("inside" to inside, "after-suspend" to afterSuspend)) {
            assertTrue(
                "log '$label' must carry a valid span context",
                record.spanContext.isValid,
            )
            assertEquals(
                "log '$label' must carry the span's trace_id",
                spanTraceId,
                record.spanContext.traceId,
            )
            assertEquals(
                "log '$label' must carry the span's span_id (correlation survives the hop)",
                spanSpanId,
                record.spanContext.spanId,
            )
        }
    }
}
