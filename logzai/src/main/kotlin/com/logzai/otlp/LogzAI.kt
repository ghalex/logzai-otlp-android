package com.logzai.otlp

import android.content.Context
import android.os.Build
import android.util.Log
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val TAG = "LogzAI"
private const val INSTRUMENTATION_SCOPE = "logzai-otlp-android"

/**
 * Thin client over the OpenTelemetry Java SDK that exports logs and spans to LogzAI via
 * OTLP/HTTP. Construct your own instance, or use the [Logzai] singleton facade.
 *
 * Logs emitted inside a [span] block automatically carry that span's `trace_id`/`span_id`
 * (the SDK reads the active [io.opentelemetry.context.Context]).
 */
class LogzAI {

    private data class Pipeline(
        val tracerProvider: SdkTracerProvider,
        val loggerProvider: SdkLoggerProvider,
        val tracer: Tracer,
        val logger: Logger,
        val serviceName: String,
        val mirrorToConsole: Boolean,
    )

    @Volatile
    private var pipeline: Pipeline? = null

    fun init(context: Context, options: LogzaiOptions) =
        init(context, options, extraLogProcessor = null)

    /**
     * Test seam: lets a test attach an extra [LogRecordProcessor] (e.g. a capturing one) so it
     * can inspect the records this client emits without decoding the exported protobuf. The
     * public [init] delegates here with no extra processor.
     */
    internal fun init(
        context: Context,
        options: LogzaiOptions,
        extraLogProcessor: LogRecordProcessor?,
    ) {
        if (pipeline != null) {
            Log.w(TAG, "LogzAI.init called more than once; ignoring.")
            return
        }

        val resource = buildResource(context.applicationContext, options)
        val timeout = Duration.ofMillis(options.timeoutMillis)

        val spanExporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(tracesUrl(options.ingestEndpoint))
            .addHeader("x-ingest-token", options.ingestToken)
            .setTimeout(timeout)
            .build()

        val logExporter = OtlpHttpLogRecordExporter.builder()
            .setEndpoint(logsUrl(options.ingestEndpoint))
            .addHeader("x-ingest-token", options.ingestToken)
            .setTimeout(timeout)
            .build()

        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(
                BatchSpanProcessor.builder(spanExporter)
                    .setMaxQueueSize(MAX_QUEUE_SIZE)
                    .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                    .setScheduleDelay(SCHEDULE_DELAY)
                    .setExporterTimeout(timeout)
                    .build(),
            )
            .build()

        val loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(logExporter)
                    .setMaxQueueSize(MAX_QUEUE_SIZE)
                    .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                    .setScheduleDelay(SCHEDULE_DELAY)
                    .setExporterTimeout(timeout)
                    .build(),
            )
            .apply { extraLogProcessor?.let { addLogRecordProcessor(it) } }
            .build()

        pipeline = Pipeline(
            tracerProvider = tracerProvider,
            loggerProvider = loggerProvider,
            tracer = tracerProvider.get(INSTRUMENTATION_SCOPE),
            logger = loggerProvider.get(INSTRUMENTATION_SCOPE),
            serviceName = options.serviceName,
            mirrorToConsole = options.mirrorToConsole,
        )
    }

    fun debug(message: String, attributes: Map<String, Any> = emptyMap()) =
        emit(Level.DEBUG, message, attributes)

    fun info(message: String, attributes: Map<String, Any> = emptyMap()) =
        emit(Level.INFO, message, attributes)

    fun warn(message: String, attributes: Map<String, Any> = emptyMap()) =
        emit(Level.WARN, message, attributes)

    fun error(message: String, attributes: Map<String, Any> = emptyMap()) =
        emit(Level.ERROR, message, attributes)

    fun exception(message: String, error: Throwable, attributes: Map<String, Any> = emptyMap()) {
        val merged = attributes + mapOf(
            "is_exception" to true,
            "exception.type" to (error::class.qualifiedName ?: error.javaClass.name),
            "exception.message" to (error.message ?: ""),
            "exception.stacktrace" to error.stackTraceToString(),
        )
        emit(Level.ERROR, message, merged)
    }

    fun <T> span(name: String, attributes: Map<String, Any> = emptyMap(), block: (Span) -> T): T {
        val p = pipeline ?: return block(Span.getInvalid())
        val span = p.tracer.spanBuilder(name)
            .setAllAttributes(attributes.toAttributes())
            .startSpan()
        return span.makeCurrent().use {
            try {
                block(span)
            } catch (t: Throwable) {
                span.recordException(t)
                span.setStatus(StatusCode.ERROR, t.message ?: t.javaClass.simpleName)
                throw t
            } finally {
                span.end()
            }
        }
    }

    /**
     * Coroutine-aware sibling of [span]: wraps a **suspending** [block] in a span whose
     * duration covers the whole suspending operation.
     *
     * Given a distinct name rather than overloading [span], because a plain lambda coerces to
     * both `(Span) -> T` and `suspend (Span) -> T`, which makes `span(name, attrs) { ... }`
     * ambiguous at every existing (synchronous) call site.
     *
     * Unlike [span] (which makes the span current via a thread-local), this runs [block] inside
     * `withContext(span.asContextElement())`, so the span is the current OpenTelemetry
     * [io.opentelemetry.context.Context] on every thread the coroutine resumes on. Logs emitted
     * via [info]/[warn]/[error]/[exception] after a suspension therefore still carry the span's
     * `trace_id`/`span_id`, even when the coroutine hops dispatchers.
     *
     * Does not block the calling thread (no `runBlocking`). On exception the span is marked
     * [StatusCode.ERROR] and the throwable rethrown; the span is always ended.
     */
    suspend fun <T> spanSuspending(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
        block: suspend (Span) -> T,
    ): T {
        val p = pipeline ?: return block(Span.getInvalid())
        val span = p.tracer.spanBuilder(name)
            .setAllAttributes(attributes.toAttributes())
            .startSpan()
        return try {
            withContext(span.asContextElement()) {
                block(span)
            }
        } catch (t: Throwable) {
            span.recordException(t)
            span.setStatus(StatusCode.ERROR, t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            span.end()
        }
    }

    fun shutdown() {
        val p = pipeline ?: return
        pipeline = null
        val joinMillis = SCHEDULE_DELAY.toMillis() + 1000
        p.loggerProvider.shutdown().join(joinMillis, TimeUnit.MILLISECONDS)
        p.tracerProvider.shutdown().join(joinMillis, TimeUnit.MILLISECONDS)
    }

    private fun emit(level: Level, message: String, attributes: Map<String, Any>) {
        val p = pipeline
        if (p == null) {
            Log.w(TAG, "LogzAI used before init(); dropping: $message")
            return
        }
        if (p.mirrorToConsole) {
            Log.println(level.logcatPriority, p.serviceName, message)
        }
        p.logger.logRecordBuilder()
            // Stamp the active OpenTelemetry Context onto the record so logs emitted inside a
            // span carry its trace_id/span_id. The SDK already defaults to Context.current()
            // at emit time; we set it explicitly so this correlation is a guarantee of *this*
            // code (and survives the dispatcher hops of the coroutine-aware `span` overload,
            // which makes the span current via withContext(span.asContextElement())).
            .setContext(io.opentelemetry.context.Context.current())
            // Set the event timestamp explicitly: without it the SDK exports
            // `time_unix_nano = 0`, and the ingest stores the log under 1970 (it reads
            // time_unix_nano with no fallback to observed_time_unix_nano), so it never
            // shows up in a recent-time view.
            .setTimestamp(Instant.now())
            .setBody(message)
            .setSeverity(level.severity)
            .setSeverityText(level.name)
            .setAllAttributes(attributes.toAttributes())
            .emit()
    }

    private fun buildResource(context: Context, options: LogzaiOptions): Resource {
        val builder = Attributes.builder()
            .put("service.name", options.serviceName)
            .put("service.namespace", options.serviceNamespace)
            .put("deployment.environment", options.environment)
            .put("os.version", Build.VERSION.RELEASE ?: "")
            .put("device.model", Build.MODEL ?: "")
            .put("device.manufacturer", Build.MANUFACTURER ?: "")
        appVersion(context)?.let { builder.put("service.version", it) }
        return Resource.getDefault().merge(Resource.create(builder.build()))
    }

    private fun appVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_QUEUE_SIZE = 2048
        const val MAX_EXPORT_BATCH_SIZE = 512
        val SCHEDULE_DELAY: Duration = Duration.ofSeconds(1)
    }
}

internal fun tracesUrl(endpoint: String): String = endpoint.trimEnd('/') + "/traces"

internal fun logsUrl(endpoint: String): String = endpoint.trimEnd('/') + "/logs"

internal fun Map<String, Any>.toAttributes(): Attributes {
    val builder = Attributes.builder()
    for ((key, value) in this) {
        when (value) {
            is String -> builder.put(AttributeKey.stringKey(key), value)
            is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
            is Long -> builder.put(AttributeKey.longKey(key), value)
            is Float -> builder.put(AttributeKey.doubleKey(key), value.toDouble())
            is Double -> builder.put(AttributeKey.doubleKey(key), value)
            else -> builder.put(AttributeKey.stringKey(key), value.toString())
        }
    }
    return builder.build()
}
