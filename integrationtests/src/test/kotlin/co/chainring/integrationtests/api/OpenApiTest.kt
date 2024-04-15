package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.model.Address
import co.chainring.integrationtests.testutils.AbnormalApiResponseException
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.apiError
import co.chainring.integrationtests.testutils.empty
import kotlinx.datetime.Clock
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Keys
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@ExtendWith(AppUnderTestRunner::class)
class OpenApiTest {

    @Test
    fun `test that documentation can be generated`() {
        assertDoesNotThrow {
            ApiClient.getOpenApiDocumentation()
        }
    }
}
