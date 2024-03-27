package co.chainring.apps.api.middleware

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.http4k.core.Filter
import org.http4k.core.HttpTransaction
import org.http4k.filter.ResponseFilters

@Serializable
data class HttpRequestLog(
    val durationMs: Long,
    val message: String,
    val req: RequestDetails,
    val res: ResponseDetails,
)

@Serializable
data class RequestDetails(
    val method: String,
    val uri: String,
    val headers: Map<String, String?>,
    val body: JsonElement,
)

@Serializable
data class ResponseDetails(
    val status: String,
    val headers: Map<String, String?>,
    val body: JsonElement,
)

object HttpTransactionLogger {

    operator fun invoke(logger: KLogger): Filter =
        ResponseFilters.ReportHttpTransaction { tx: HttpTransaction ->
            val log = HttpRequestLog(
                message = "${tx.request.method.name} ${tx.request.uri} returned ${tx.response.status} in ${tx.duration.toMillis()}ms.",
                durationMs = tx.duration.toMillis(),
                req = RequestDetails(
                    method = tx.request.method.name,
                    uri = tx.request.uri.toString(),
                    headers = tx.request.headers.toMap(),
                    body = when (val body = tx.request.bodyString()) {
                        "" -> JsonNull
                        else -> Json.parseToJsonElement(body)
                    },
                ),
                res = ResponseDetails(
                    status = tx.response.status.toString(),
                    headers = tx.response.headers.toMap(),
                    body = when (val body = tx.response.bodyString()) {
                        "" -> JsonNull
                        else -> Json.parseToJsonElement(body)
                    },
                ),
            )

            logger.info { Json.encodeToString(HttpRequestLog.serializer(), log) }
        }
}
