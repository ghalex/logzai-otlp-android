package com.logzai.otlp

/**
 * Configuration for [LogzAI.init].
 *
 * Mirrors the options accepted by the Python (`logzai-otlp-py`) and JavaScript
 * (`logzai-otlp-js`) clients. Logs are exported to `<ingestEndpoint>/logs` and spans to
 * `<ingestEndpoint>/traces`, authenticated with the `x-ingest-token` header.
 */
data class LogzaiOptions(
    /** LogzAI ingest token, sent as the `x-ingest-token` header on every export. */
    val ingestToken: String,
    /** Base OTLP/HTTP endpoint; `/logs` and `/traces` are appended automatically. */
    val ingestEndpoint: String = "https://ingest.logzai.com",
    /** `service.name` resource attribute. */
    val serviceName: String = "app",
    /** `service.namespace` resource attribute. */
    val serviceNamespace: String = "default",
    /** `deployment.environment` resource attribute. */
    val environment: String = "prod",
    /** When true, every log is also written to logcat under the [serviceName] tag. */
    val mirrorToConsole: Boolean = false,
    /** Per-request export timeout in milliseconds. */
    val timeoutMillis: Long = 10_000L,
)
