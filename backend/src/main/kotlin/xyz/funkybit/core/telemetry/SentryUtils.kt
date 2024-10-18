package xyz.funkybit.core.telemetry

import io.github.oshai.kotlinlogging.KotlinLogging
import io.sentry.Instrumenter
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.opentelemetry.OpenTelemetryLinkErrorEventProcessor

object SentryUtils {
    private val logger = KotlinLogging.logger {}

    val enabled = System.getenv("SENTRY_DSN")?.isNotEmpty() ?: false

    fun init() {
        if (enabled) {
            logger.debug { "Initializing Sentry" }
            Sentry.init { options: SentryOptions ->
                options.dsn = System.getenv("SENTRY_DSN")
                options.tracesSampleRate = System.getenv("TRACES_SAMPLE_RATE").toDoubleOrNull() ?: 1.0
                options.environment = System.getenv("ENV_NAME")
                options.instrumenter = Instrumenter.OTEL
                options.addEventProcessor(OpenTelemetryLinkErrorEventProcessor())
            }
            logger.debug { "Sentry initialization complete" }
        } else {
            logger.debug { "Skipping Sentry initialization" }
        }
    }
}
