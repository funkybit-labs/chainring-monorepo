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
class ApiAuthenticationTest {

    @Test
    fun `test missing authorization header`() {
        verifyFailure("Authorization header is missing") {
            ApiClient.listOrders { Headers.empty }
        }
    }

    @Test
    fun `test invalid authorization scheme`() {
        verifyFailure("Invalid authentication scheme") {
            ApiClient.listOrders { mapOf("Authorization" to "signature token").toHeaders() }
        }
    }

    @Test
    fun `test invalid token format`() {
        verifyFailure("Invalid token format") {
            ApiClient.listOrders { mapOf("Authorization" to "Bearer token").toHeaders() }
        }
    }

    @Test
    fun `test token issue and expiry dates`() {
        // timestamp is far in future
        verifyFailure("Token is expired or not valid yet") {
            ApiClient.listOrders {
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
            ApiClient.listOrders {
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
            ApiClient.listOrders {
                mapOf(
                    "Authorization" to "Bearer ${
                        ApiClient.issueAuthToken(
                            ecKeyPair = Keys.createEcKeyPair(),
                            address = Address("0x${Keys.getAddress(Keys.createEcKeyPair())}"),
                        )
                    }",
                ).toHeaders()
            }
        }

        verifyFailure("Invalid signature") {
            ApiClient.listOrders {
                mapOf(
                    "Authorization" to "Bearer ${ApiClient.issueAuthToken()}abcdef",
                ).toHeaders()
            }
        }
    }

    @Test
    fun `test success`() {
        val apiClient = ApiClient.initWallet()
        assertDoesNotThrow {
            apiClient.listOrders()
        }
    }

    private fun verifyFailure(expectedError: String, call: () -> Unit) {
        assertThrows<AbnormalApiResponseException> {
            call()
        }.also {
            assertEquals(HTTP_UNAUTHORIZED, it.response.code)
            assertEquals(
                ApiError(ReasonCode.AuthenticationError, expectedError),
                it.response.apiError(),
            )
        }
    }
}
