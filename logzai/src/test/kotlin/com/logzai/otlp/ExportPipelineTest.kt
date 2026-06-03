package com.logzai.otlp

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Exercises the real export path (init -> emit -> batch -> OTLP/HTTP POST -> flush) against
 * a MockWebServer. Verifies a log reaches `/logs` and a span reaches `/traces`, both bearing
 * the `x-ingest-token` header. Does not decode the protobuf body — only asserts the requests
 * actually leave the SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportPipelineTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        // OTLP exporter treats any 200 as success; an empty body is fine for this assertion.
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `log and span are exported with the ingest token`() {
        val logzai = LogzAI()
        logzai.init(
            RuntimeEnvironment.getApplication(),
            LogzaiOptions(
                ingestToken = "test-token",
                ingestEndpoint = server.url("/").toString(),
            ),
        )

        logzai.span("op") {
            logzai.info("inside span", mapOf("k" to 1))
        }
        logzai.shutdown()

        val requests = buildList {
            repeat(2) { server.takeRequest(5, TimeUnit.SECONDS)?.let(::add) }
        }
        val byPath = requests.associateBy { it.requestPath() }

        assertTrue("expected a POST to /logs, saw ${byPath.keys}", byPath.containsKey("/logs"))
        assertTrue("expected a POST to /traces, saw ${byPath.keys}", byPath.containsKey("/traces"))
        requests.forEach { assertEquals("test-token", it.getHeader("x-ingest-token")) }
    }

    private fun RecordedRequest.requestPath(): String? = path
}
