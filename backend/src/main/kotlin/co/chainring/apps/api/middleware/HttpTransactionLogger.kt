package co.chainring.apps.api.middleware

import io.github.oshai.kotlinlogging.KLogger
import org.http4k.core.Filter
import org.http4k.core.HttpTransaction
import org.http4k.filter.ResponseFilters

object HttpTransactionLogger {
    operator fun invoke(logger: KLogger): Filter =
        ResponseFilters.ReportHttpTransaction { tx: HttpTransaction ->
            logger.info {
                "${tx.request.method.name} ${tx.request.uri} returned ${tx.response.status} in ${tx.duration.toMillis()}ms. ${
                    headerInfo(
                        tx,
                        "X-Forwarded-For",
                        "from",
                    )
                }"
            }
            try {
                logger.info {
                    """Request headers: ${
                        tx.request.headers.joinToString("\n ") { "${it.first}: ${it.second}" }
                    }\nRequest body: '${
                        tx.request.bodyString()
                    }'\nResponse body: '${
                        tx.response.bodyString()
                    }'"""
                }
            } catch (e: Exception) {
                logger.error(e) { "could not log request and response" }
            }
        }

    private fun headerInfo(
        tx: HttpTransaction,
        headerName: String,
        label: String,
    ) = tx.request.header(headerName)?.let { " $label $it" } ?: ""
}
