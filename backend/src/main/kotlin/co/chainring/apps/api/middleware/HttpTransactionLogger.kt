package co.chainring.apps.api.middleware

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.message.ObjectMessage
import org.http4k.core.Filter
import org.http4k.core.HttpTransaction
import org.http4k.filter.ResponseFilters

object HttpTransactionLogger {
    private val log4jLogger: Logger = LogManager.getLogger()

    operator fun invoke(): Filter =
        ResponseFilters.ReportHttpTransaction { tx: HttpTransaction ->
            log4jLogger.info(
                ObjectMessage(
                    mapOf(
                        "info" to "${tx.request.method.name} ${tx.request.uri} returned ${tx.response.status} in ${tx.duration.toMillis()}ms.",
                        "req" to mapOf(
                            "method" to tx.request.method.name,
                            "uri" to tx.request.uri.toString(),
                            "headers" to tx.request.headers.toMap(),
                            "body" to tx.request.bodyString(),
                        ),
                        "res" to mapOf(
                            "durationMs" to tx.duration.toMillis(),
                            "status" to tx.response.status.toString(),
                            "headers" to tx.response.headers.toMap(),
                            "body" to tx.response.bodyString(),
                        ),
                    )
                ))
        }
}
