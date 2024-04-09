package co.chainring.apps.api

import co.chainring.apps.api.model.processingError
import co.chainring.apps.api.model.unexpectedError
import co.chainring.core.model.ExchangeError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.Response

object ApiUtils {

    private val logger = KotlinLogging.logger {}

    fun runCatchingValidation(logic: () -> Response): Response {
        return try {
            logic()
        } catch (ee: ExchangeError) {
            processingError(ee.message!!)
        } catch (e: Exception) {
            logger.error(e) { "Unexpected API error " }
            unexpectedError()
        }
    }
}
