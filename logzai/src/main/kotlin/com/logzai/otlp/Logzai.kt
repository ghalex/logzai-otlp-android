package com.logzai.otlp

import android.content.Context
import io.opentelemetry.api.trace.Span

/**
 * Process-wide singleton facade over a single [LogzAI] instance, so callers can write
 * `Logzai.init(...)` / `Logzai.info(...)` directly. Mirrors the default `logzai` instance
 * exported by the Python and JavaScript clients.
 */
object Logzai {

    private val delegate = LogzAI()

    fun init(context: Context, options: LogzaiOptions) = delegate.init(context, options)

    fun debug(message: String, attributes: Map<String, Any> = emptyMap()) =
        delegate.debug(message, attributes)

    fun info(message: String, attributes: Map<String, Any> = emptyMap()) =
        delegate.info(message, attributes)

    fun warn(message: String, attributes: Map<String, Any> = emptyMap()) =
        delegate.warn(message, attributes)

    fun error(message: String, attributes: Map<String, Any> = emptyMap()) =
        delegate.error(message, attributes)

    fun exception(message: String, error: Throwable, attributes: Map<String, Any> = emptyMap()) =
        delegate.exception(message, error, attributes)

    fun <T> span(name: String, attributes: Map<String, Any> = emptyMap(), block: (Span) -> T): T =
        delegate.span(name, attributes, block)

    /**
     * Coroutine-aware sibling of [span]: wraps a suspending [block] in a span whose duration
     * covers the whole suspending operation, propagating the span across dispatcher hops so
     * logs emitted inside it keep the span's `trace_id`/`span_id`. See [LogzAI.spanSuspending].
     */
    suspend fun <T> spanSuspending(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
        block: suspend (Span) -> T,
    ): T = delegate.spanSuspending(name, attributes, block)

    fun shutdown() = delegate.shutdown()
}
