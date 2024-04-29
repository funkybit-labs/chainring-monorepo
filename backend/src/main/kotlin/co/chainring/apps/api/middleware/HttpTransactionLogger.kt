package co.chainring.apps.api.middleware

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.message.ObjectMessage
import org.http4k.core.Filter
import kotlin.time.Duration.Companion.nanoseconds

object HttpTransactionLogger {
    private val log4jLogger: Logger = LogManager.getLogger()

    operator fun invoke(): Filter = Filter { next ->
        { request ->
            Tracer.newSpan(ServerSpans.app) {
                next(request)
            }.let { response ->
                response.headers(
                    listOfNotNull(
                        "Server-Timing" to Tracer.serialize(),
                        request.header("X-Amzn-Trace-Id")?.let { "X-Amzn-Trace-Id" to it },
                    ),
                )
            }.also { response ->
                val durationMillis = Tracer.getDuration(ServerSpans.app)?.nanoseconds?.inWholeMilliseconds

                log4jLogger.info(
                    ObjectMessage(
                        mapOf(
                            "summary" to "${request.method.name} ${request.uri} returned ${response.status} in ${durationMillis}ms.",
                            "req" to mapOf(
                                "method" to request.method.name,
                                "uri" to request.uri.toString(),
                                "headers" to request.headers.toMap(),
                                "body" to request.bodyString(),
                            ),
                            "res" to mapOf(
                                "durationMs" to durationMillis,
                                "status" to response.status.toString(),
                                "headers" to response.headers.toMap(),
                                "body" to response.bodyString(),
                            ),
                        ),
                    ),
                )
            }
        }
    }
}
