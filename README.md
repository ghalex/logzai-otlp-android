# logzai-otlp-android

LogzAI OTLP client for Android — the Kotlin sibling of [`logzai-otlp-py`] and
[`logzai-otlp-js`]. A thin wrapper over the OpenTelemetry Java SDK that ships **logs and
spans** to LogzAI over OTLP/HTTP.

This is the **v1 core**: logs + spans only. No plugins, no AI/gen-ai integrations.

## Install

Published to Maven Central — no extra repository needed (`mavenCentral()` is already in
every Android project).

`gradle/libs.versions.toml`:

```toml
[versions]
logzai = "0.2.1"

[libraries]
logzai-otlp-android = { group = "com.logzai", name = "logzai-otlp-android", version.ref = "logzai" }
```

Module `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.logzai.otlp.android)
}
```

The library declares the `INTERNET` permission and merges it into your app. See
[PUBLISHING.md](PUBLISHING.md) for how releases are cut.

## Usage

```kotlin
import com.logzai.otlp.Logzai
import com.logzai.otlp.LogzaiOptions

// Once, early in Application.onCreate()
Logzai.init(
    context = this,
    options = LogzaiOptions(
        ingestToken = "your-ingest-token",
        ingestEndpoint = "https://ingest.logzai.com", // base; /logs and /traces are appended
        serviceName = "my-android-app",
        serviceNamespace = "examples",
        environment = "production",
        mirrorToConsole = true, // also write to logcat
    ),
)

// Logs
Logzai.info("User logged in", mapOf("userId" to "123", "method" to "oauth"))
Logzai.warn("Cache miss")
Logzai.error("Checkout failed", mapOf("orderId" to "order-456"))

try {
    riskyWork()
} catch (e: Exception) {
    // adds is_exception / exception.type / exception.message / exception.stacktrace
    Logzai.exception("Operation failed", e)
}

// Spans — logs emitted inside the block carry the span's trace_id/span_id automatically
Logzai.span("order-processing", mapOf("orderId" to "order-456")) { span ->
    Logzai.info("Processing order")
    Logzai.span("payment") {
        Logzai.info("Charging card")
    }
}

// On shutdown (e.g. Application.onTerminate or a lifecycle hook), flush pending data
Logzai.shutdown()
```

### Suspending spans (coroutines)

The synchronous `span { ... }` can't wrap suspending code (its block isn't a `suspend`
lambda) and its span is made current via a thread-local, which does not follow a coroutine
across suspension points. For long-running suspend operations — e.g. a payment that suspends
across Room DB and a card-terminal call — use `spanSuspending`:

```kotlin
// block is a suspend lambda; the span's duration covers the whole suspending operation.
suspend fun chargeOrder(orderId: String): Receipt =
    Logzai.spanSuspending("payment", mapOf("orderId" to orderId)) { span ->
        Logzai.info("Charging card")          // carries the span's trace_id/span_id
        val auth = cardTerminal.authorize()   // suspends, may resume on another thread
        db.recordPayment(auth)                // suspends again
        Logzai.info("Payment recorded")       // still correlated to the span
        Receipt(auth)
    }
```

`spanSuspending` runs the block inside `withContext(span.asContextElement())`, so the span
stays the current OpenTelemetry context on every thread the coroutine resumes on — logs
emitted after a suspension keep their `trace_id`/`span_id`. It never blocks the calling
thread (no `runBlocking`). On exception it records the throwable, marks the span
`StatusCode.ERROR`, and rethrows; the span is always ended.

> It's a distinct name (not an overload of `span`) on purpose: a plain lambda coerces to both
> `(Span) -> T` and `suspend (Span) -> T`, so overloading would make every existing
> `span(name) { ... }` call ambiguous.

Use the `Logzai` object for the process-wide singleton, or construct your own `LogzAI()`
instance if you need several independent pipelines.

## Configuration

| Option            | Default                        | Description |
|-------------------|--------------------------------|-------------|
| `ingestToken`     | — (required)                   | Sent as the `x-ingest-token` header. |
| `ingestEndpoint`  | `https://ingest.logzai.com`    | Base OTLP/HTTP endpoint; `/logs` and `/traces` appended. |
| `serviceName`     | `app`                          | `service.name` resource attribute. |
| `serviceNamespace`| `default`                      | `service.namespace` resource attribute. |
| `environment`     | `prod`                         | `deployment.environment` resource attribute. |
| `mirrorToConsole` | `false`                        | Also write each log to logcat. |
| `timeoutMillis`   | `10000`                        | Per-export HTTP timeout. |

Device/app attributes (`service.version`, `os.version`, `device.model`,
`device.manufacturer`) are attached to every record automatically.

## Notes

- `span { ... }` is synchronous — it runs the block on the calling thread. For suspending
  operations use `spanSuspending { ... }` (see [Suspending spans](#suspending-spans-coroutines)).
- `shutdown()` is caller-driven; call it when the app is going away to flush the
  in-memory batch (logs/spans are not persisted across process death).

## Changelog

### 0.2.1

- `init()` never throws: a misconfiguration (e.g. a blank/malformed `ingestEndpoint`) is
  caught and logged, and telemetry is disabled rather than crashing the host app. After a
  failed or absent init, `span`/`spanSuspending` still run their block and the log methods
  drop silently — using the library can never crash an app because tracing isn't working.

### 0.2.0

- Add `spanSuspending(name, attributes) { ... }` — a coroutine-aware span whose duration
  covers a whole suspending operation and whose context follows the coroutine across
  dispatcher hops, so logs emitted after a suspension stay correlated to the span.

### 0.1.1

- v1 core: logs + spans over OTLP/HTTP.

[`logzai-otlp-py`]: ../logzai-otlp-py
[`logzai-otlp-js`]: ../logzai-otlp-js
