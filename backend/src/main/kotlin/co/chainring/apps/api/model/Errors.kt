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
    OrderNotFound,

    WithdrawalNotFound,
    DepositNotFound,

    SignatureNotValid,
    UnexpectedError,
    AuthenticationError,

    ProcessingError,

    RejectedBySequencer,

    ChainNotSupported,

    SignupRequired,
}

val jsonWithDefaults = Json { encodeDefaults = true }

class RequestProcessingError(val httpStatus: Status, val error: ApiError) : Exception("Error during API request processing") {
    constructor(httpStatus: Status, reason: ReasonCode, message: String) : this(httpStatus, ApiError(reason, message))
    constructor(reason: ReasonCode, message: String) : this(Status.UNPROCESSABLE_ENTITY, reason, message)
    constructor(message: String) : this(Status.UNPROCESSABLE_ENTITY, ReasonCode.ProcessingError, message)
}

fun errorResponse(
    status: Status,
    error: ApiError,
): Response =
    Response(status)
        .body(jsonWithDefaults.encodeToString(ApiErrors(listOf(error))))
        .with(Header.CONTENT_TYPE of ContentType.APPLICATION_JSON)

fun notFoundError(reason: ReasonCode, message: String): Response = errorResponse(Status.NOT_FOUND, ApiError(reason, message))

fun processingError(message: String): Response = errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ProcessingError, message))

fun unexpectedError(): Response = errorResponse(Status.INTERNAL_SERVER_ERROR, ApiError(ReasonCode.UnexpectedError, "Unexpected Error"))

val orderNotFoundError = notFoundError(ReasonCode.OrderNotFound, "Requested order does not exist")
