package co.chainring.apps.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Header

@Serializable
data class ApiError(
    val reason: ReasonCode,
    val message: String,
    val displayMessage: String = message,
)

@Serializable
data class ApiErrors(val errors: List<ApiError>)

@Serializable
enum class ReasonCode {
    UnexpectedError,
    TimeoutError,
}

val jsonWithDefaults = Json { encodeDefaults = true }

class RequestProcessingError(val errorResponse: Response) : Exception("Error during API request processing")

fun errorResponse(status: Status, error: ApiError): Response =
    Response(status)
        .body(jsonWithDefaults.encodeToString(ApiErrors(listOf(error))))
        .with(Header.CONTENT_TYPE of ContentType.APPLICATION_JSON)
