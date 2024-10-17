package xyz.funkybit.core.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators.create
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.sentry.opentelemetry.SentryPropagator
import io.sentry.opentelemetry.SentrySpanProcessor

val openTelemetry: OpenTelemetry = when (SentryUtils.enabled) {
    true -> {
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SentrySpanProcessor()).build(),
            )
            .setPropagators(create(SentryPropagator()))
            .buildAndRegisterGlobal()
    }
    false -> OpenTelemetry.noop()
}

val tracer: Tracer = openTelemetry.getTracer("internal-tracing")

inline fun <T> span(name: String, body: (Span) -> T): T {
    val span = tracer
        .spanBuilder(name)
        .setSpanKind(SpanKind.INTERNAL)
        .setParent(Context.current().with(Span.current()))
        .startSpan()

    return try {
        span.makeCurrent().use {
            body(span)
        }
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}

inline fun <T> rootSpan(name: String, body: (Span) -> T): T {
    val span = tracer
        .spanBuilder(name)
        .setSpanKind(SpanKind.INTERNAL)
        .setNoParent()
        .startSpan()

    return try {
        span.makeCurrent().use {
            body(span)
        }
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}
