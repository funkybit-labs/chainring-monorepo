package xyz.funkybit.core.telemetry

import io.sentry.Instrumenter
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.opentelemetry.OpenTelemetryLinkErrorEventProcessor

object SentryUtils {
    val enabled = System.getenv("SENTRY_DSN")?.let { true } ?: false

    fun init() {
        if (enabled) {
            Sentry.init { options: SentryOptions ->
                options.dsn = System.getenv("SENTRY_DSN")
                options.tracesSampleRate = System.getenv("TRACES_SAMPLE_RATE").toDoubleOrNull() ?: 1.0
                options.environment = System.getenv("ENV_NAME")
                options.instrumenter = Instrumenter.OTEL
                options.addEventProcessor(OpenTelemetryLinkErrorEventProcessor())
            }
        }
    }
}
