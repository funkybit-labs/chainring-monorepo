package xyz.funkybit.integrationtests.api

import arrow.core.Either
import kotlinx.datetime.Clock
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Keys
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.ApiCallFailure
import xyz.funkybit.integrationtests.utils.ApiClient
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.integrationtests.utils.assertSuccess
import xyz.funkybit.integrationtests.utils.empty
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@ExtendWith(AppUnderTestRunner::class)
class ApiAuthenticationTest {

    @Test
    fun `test missing authorization header`() {
        verifyFailure("Authorization header is missing") {
            ApiClient.tryListOrders(emptyList(), null) { Headers.empty }
        }
    }

    @Test
    fun `test invalid authorization scheme`() {
        verifyFailure("Invalid authentication scheme") {
            ApiClient.tryListOrders(emptyList(), null) { mapOf("Authorization" to "signature token").toHeaders() }
        }
    }

    @Test
    fun `test invalid token format`() {
        verifyFailure("Invalid token format") {
            ApiClient.tryListOrders(emptyList(), null) { mapOf("Authorization" to "Bearer token").toHeaders() }
        }
    }

    @Test
    fun `test token issue and expiry dates`() {
        // timestamp is far in future
        verifyFailure("Token is expired or not valid yet") {
            ApiClient.tryListOrders(emptyList(), null) {
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
            ApiClient.tryListOrders(emptyList(), null) {
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
            ApiClient.tryListOrders(emptyList(), null) {
                mapOf(
                    "Authorization" to "Bearer ${
                        ApiClient.issueAuthToken(
                            keyPair = WalletKeyPair.EVM.generate(),
                            address = "0x${Keys.getAddress(Keys.createEcKeyPair())}",
                        )
                    }",
                ).toHeaders()
            }
        }

        verifyFailure("Invalid signature") {
            ApiClient.tryListOrders(emptyList(), null) {
                mapOf(
                    "Authorization" to "Bearer ${ApiClient.issueAuthToken()}abcdef",
                ).toHeaders()
            }
        }
    }

    @Test
    fun `test success`() {
        val apiClient = ApiClient()
        apiClient.tryListOrders(emptyList(), null).assertSuccess()
    }

    private fun verifyFailure(expectedError: String, call: () -> Either<ApiCallFailure, Any>) {
        call().assertError(
            expectedHttpCode = HTTP_UNAUTHORIZED,
            expectedError = ApiError(ReasonCode.AuthenticationError, expectedError),
        )
    }
}
