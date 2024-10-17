package xyz.funkybit.core.telemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.IOException

fun OkHttpClient.Builder.withTracing(): OkHttpClient.Builder {
    val tracer: Tracer = openTelemetry.getTracer("http-client-instrumentation")

    return addInterceptor { chain: Interceptor.Chain ->
        val request = chain.request()

        // record span only when trace is already created
        if (!Span.current().spanContext.isValid) {
            return@addInterceptor chain.proceed(request)
        }

        // making a copy of request body since it can be consumed only once
        val requestBodyCopy = request.newBuilder().build().body?.let { body ->
            val requestBuffer = Buffer()
            body.writeTo(requestBuffer)
            requestBuffer.readUtf8()
        } ?: ""

        val span = tracer.spanBuilder(request.method + " " + request.url)
            .setParent(Context.current().with(Span.current()))
            .setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT)
            .startSpan()

        try {
            val response = chain.proceed(request)

            if (response.isSuccessful) {
                span.setStatus(StatusCode.OK)
            } else {
                span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
            }

            span.setAttribute("http.method", request.method)
            span.setAttribute("http.url", request.url.toString())
            span.setAttribute("http.status_code", response.code.toString())
            span.setAttribute("http.body", requestBodyCopy)

            return@addInterceptor response
        } catch (e: IOException) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            throw e
        } finally {
            span.end()
        }
    }
}
