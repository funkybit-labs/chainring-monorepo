package co.chainring.integrationtests.api

import arrow.core.Either
import co.chainring.apps.api.middleware.TelegramMiniAppUserData
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.tma.ClaimRewardApiRequest
import co.chainring.apps.api.model.tma.GetUserApiResponse
import co.chainring.core.model.telegram.TelegramUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGoal
import co.chainring.core.utils.crPoints
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.ApiCallFailure
import co.chainring.integrationtests.utils.TelegramMiniAppApiClient
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.empty
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.http4k.core.toUrlFormEncoded
import org.http4k.format.KotlinxSerialization.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class TelegramMiniAppApiTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun authentication() {
        verifyAuthFailure("Authorization header is missing") {
            TelegramMiniAppApiClient.tryGetUser { Headers.empty }
        }

        verifyAuthFailure("Invalid authentication scheme") {
            TelegramMiniAppApiClient.tryGetUser { mapOf("Authorization" to "signature token").toHeaders() }
        }

        verifyAuthFailure("Hash is missing") {
            val user = TelegramMiniAppUserData(
                TelegramUserId(123L),
                firstName = "John",
                lastName = "Doe",
                languageCode = "en",
                allowsWriteToPm = true,
            )

            val token = listOf(
                Pair("user", json.encodeToString(user)),
            ).toUrlFormEncoded()

            TelegramMiniAppApiClient.tryGetUser { mapOf("Authorization" to "Bearer $token").toHeaders() }
        }

        verifyAuthFailure("Invalid signature") {
            val user = TelegramMiniAppUserData(
                TelegramUserId(123L),
                firstName = "John",
                lastName = "Doe",
                languageCode = "en",
                allowsWriteToPm = true,
            )

            val token = listOf(
                Pair("user", json.encodeToString(user)),
                Pair("hash", "invalid".toByteArray().toHexString()),
            ).toUrlFormEncoded()

            TelegramMiniAppApiClient.tryGetUser { mapOf("Authorization" to "Bearer $token").toHeaders() }
        }
    }

    @Test
    fun signup() {
        val apiClient = TelegramMiniAppApiClient(TelegramUserId(123L))

        val result = apiClient.signUp().also {
            assertEquals("0".crPoints(), it.balance)
            assertEquals(
                listOf(
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.GithubSubscription,
                        reward = "1000".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.DiscordSubscription,
                        reward = "1000".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.MediumSubscription,
                        reward = "1000".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.LinkedinSubscription,
                        reward = "1000".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.XSubscription,
                        reward = "1000".crPoints(),
                        achieved = false,
                    ),
                ),
                it.goals,
            )
        }

        assertEquals(result, apiClient.getUser())

        // verify idempotency
        assertEquals(result, apiClient.signUp())
    }

    @Test
    fun claimReward() {
        val apiClient = TelegramMiniAppApiClient(TelegramUserId(123L))
        apiClient.signUp()

        apiClient
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.GithubSubscription))
            .also {
                assertEquals("1000".crPoints(), it.balance)
                assertEquals(
                    listOf(
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.GithubSubscription,
                            reward = "1000".crPoints(),
                            achieved = true,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.DiscordSubscription,
                            reward = "1000".crPoints(),
                            achieved = false,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.MediumSubscription,
                            reward = "1000".crPoints(),
                            achieved = false,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.LinkedinSubscription,
                            reward = "1000".crPoints(),
                            achieved = false,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.XSubscription,
                            reward = "1000".crPoints(),
                            achieved = false,
                        ),
                    ),
                    it.goals,
                )
            }

        // verify idempotency
        apiClient
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.GithubSubscription))
            .also {
                assertEquals("1000".crPoints(), it.balance)
            }
    }

    private fun verifyAuthFailure(expectedError: String, call: () -> Either<ApiCallFailure, Any>) {
        call().assertError(
            expectedHttpCode = HTTP_UNAUTHORIZED,
            expectedError = ApiError(ReasonCode.AuthenticationError, expectedError),
        )
    }
}
