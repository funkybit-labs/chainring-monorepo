package xyz.funkybit.apps.api.middleware

import io.github.oshai.kotlinlogging.KLogger
import io.sentry.Sentry
import org.http4k.core.Filter
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.errorResponse

object RequestProcessingExceptionHandler {
    operator fun invoke(logger: KLogger): Filter =
        Filter { next ->
            {
                    request ->
                try {
                    next(request)
                } catch (e: Exception) {
                    when (e) {
                        is RequestProcessingError -> errorResponse(e.httpStatus, e.error)
                        else -> {
                            Sentry.captureException(e)
                            logger.error(e) { "Exception during request processing. Request: ${request.method.name} ${request.uri}" }
                            errorResponse(
                                INTERNAL_SERVER_ERROR,
                                ApiError(
                                    ReasonCode.UnexpectedError,
                                    "An unexpected error has occurred. Please, contact support if this issue persists.",
                                ),
                            )
                        }
                    }
                }
            }
        }
}
