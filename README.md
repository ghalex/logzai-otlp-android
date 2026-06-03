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
logzai = "0.1.0"

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

- Spans are synchronous in v1 — `span { ... }` runs the block on the calling thread.
- `shutdown()` is caller-driven; call it when the app is going away to flush the
  in-memory batch (logs/spans are not persisted across process death).

[`logzai-otlp-py`]: ../logzai-otlp-py
[`logzai-otlp-js`]: ../logzai-otlp-js
