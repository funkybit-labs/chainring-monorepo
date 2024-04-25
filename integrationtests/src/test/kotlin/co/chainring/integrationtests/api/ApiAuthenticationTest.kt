package co.chainring.integrationtests.api

import arrow.core.Either
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.ApiCallFailure
import co.chainring.integrationtests.utils.ApiClient
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.assertSuccess
import co.chainring.integrationtests.utils.empty
import kotlinx.datetime.Clock
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Keys
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@ExtendWith(AppUnderTestRunner::class)
class ApiAuthenticationTest {

    @Test
    fun `test missing authorization header`() {
        verifyFailure("Authorization header is missing") {
            ApiClient.tryListOrders { Headers.empty }
        }
    }

    @Test
    fun `test invalid authorization scheme`() {
        verifyFailure("Invalid authentication scheme") {
            ApiClient.tryListOrders { mapOf("Authorization" to "signature token").toHeaders() }
        }
    }

    @Test
    fun `test invalid token format`() {
        verifyFailure("Invalid token format") {
            ApiClient.tryListOrders { mapOf("Authorization" to "Bearer token").toHeaders() }
        }
    }

    @Test
    fun `test token issue and expiry dates`() {
        // timestamp is far in future
        verifyFailure("Token is expired or not valid yet") {
            ApiClient.tryListOrders {
                mapOf(
                    "Authorization" to "Bearer ${
                        ApiClient.issueAuthToken(
                            timestamp = Clock.System.now() + 100.hours,
                        )
                    }",
                ).toHeaders()
            }
        }

        // validity exceed maximum allowed interval
        verifyFailure("Token is expired or not valid yet") {
            ApiClient.tryListOrders {
                mapOf(
                    "Authorization" to "Bearer ${
                        ApiClient.issueAuthToken(
                            timestamp = Clock.System.now() - 31.days,
                        )
                    }",
                ).toHeaders()
            }
        }
    }

    @Test
    fun `test recovered from signature address does not match address on message`() {
        verifyFailure("Invalid signature") {
            ApiClient.tryListOrders {
                mapOf(
                    "Authorization" to "Bearer ${
                        ApiClient.issueAuthToken(
                            ecKeyPair = Keys.createEcKeyPair(),
                            address = "0x${Keys.getAddress(Keys.createEcKeyPair())}",
                        )
                    }",
                ).toHeaders()
            }
        }

        verifyFailure("Invalid signature") {
            ApiClient.tryListOrders {
                mapOf(
                    "Authorization" to "Bearer ${ApiClient.issueAuthToken()}abcdef",
                ).toHeaders()
            }
        }
    }

    @Test
    fun `test success`() {
        val apiClient = ApiClient()
        apiClient.tryListOrders().assertSuccess()
    }

    private fun verifyFailure(expectedError: String, call: () -> Either<ApiCallFailure, Any>) {
        call().assertError(
            expectedHttpCode = HTTP_UNAUTHORIZED,
            expectedError = ApiError(ReasonCode.AuthenticationError, expectedError),
        )
    }
}
