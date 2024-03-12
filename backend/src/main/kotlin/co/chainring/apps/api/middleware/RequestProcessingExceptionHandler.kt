package co.chainring.apps.api.middleware

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.model.errorResponse
import io.github.oshai.kotlinlogging.KLogger
import org.http4k.core.Filter
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR

object RequestProcessingExceptionHandler {
    operator fun invoke(logger: KLogger): Filter = Filter { next ->
        {
                request ->
            try {
                next(request)
            } catch (e: Exception) {
                when (e) {
                    is RequestProcessingError -> e.errorResponse
                    else -> {
                        logger.error(e) { "Exception during request processing. Request: ${request.method.name} ${request.uri}" }
                        errorResponse(
                            INTERNAL_SERVER_ERROR,
                            ApiError(ReasonCode.UnexpectedError, "An unexpected error has occurred. Please, contact support if this issue persists."),
                        )
                    }
                }
            }
        }
    }
}
