# OpenTelemetry uses reflection for autoconfigure SPI; keep its service files and classes.
-keep class io.opentelemetry.** { *; }
-dontwarn io.opentelemetry.**
