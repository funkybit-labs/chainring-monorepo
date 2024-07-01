package co.chainring.integrationtests.api

import arrow.core.Either
import co.chainring.apps.api.middleware.TelegramMiniAppUserData
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.tma.ClaimRewardApiRequest
import co.chainring.apps.api.model.tma.GetUserApiResponse
import co.chainring.apps.api.model.tma.ReactionTimeApiRequest
import co.chainring.core.model.telegram.TelegramUserId
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGoal
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserRewardEntity
import co.chainring.core.utils.crPoints
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.ApiCallFailure
import co.chainring.integrationtests.utils.TelegramMiniAppApiClient
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.empty
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.http4k.core.toUrlFormEncoded
import org.http4k.format.KotlinxSerialization.json
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
            assertEquals("20".crPoints(), it.balance)

            assertEquals(1, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(1, it.checkInStreak.gameTickets)

            assertEquals(5, it.invites)
            assertEquals("980".crPoints(), it.nextMilestoneIn)
            assertNull(it.lastMilestone)

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
        apiClient.signUp().also {
            assertEquals("20".crPoints(), it.balance)
        }

        apiClient
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.GithubSubscription))
            .also {
                assertEquals("1020".crPoints(), it.balance)
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
                assertEquals("1020".crPoints(), it.balance)
            }
    }

    @Test
    fun reactionTimeGame() {
        TelegramMiniAppApiClient(TelegramUserId(555L)).also {
            it.signUp()
            it.recordReactionTime(ReactionTimeApiRequest(100))
        }
        TelegramMiniAppApiClient(TelegramUserId(556L)).also {
            it.signUp()
            it.recordReactionTime(ReactionTimeApiRequest(300))
        }

        val apiClient = TelegramMiniAppApiClient(TelegramUserId(123L))
        apiClient.signUp()
        assertEquals(1, apiClient.getUser().gameTickets)
        assertEquals("20".crPoints(), apiClient.getUser().balance)

        apiClient
            .recordReactionTime(ReactionTimeApiRequest(200))
            .also {
                assertEquals("70".crPoints(), it.balance)
                assertEquals(50, it.percentile)
                assertEquals(0, "50".crPoints().compareTo(it.reward))
            }

        apiClient
            .tryRecordReactionTime(ReactionTimeApiRequest(200))
            .also {
                assertTrue { it.isLeft() }
                val error = it.leftOrNull()
                assertNotNull(error)
                assertEquals(422, error.httpCode)
                assertEquals("No game tickets available", error.error?.message)
            }

        apiClient
            .tryRecordReactionTime(ReactionTimeApiRequest(0))
            .also {
                assertTrue { it.isLeft() }
                val error = it.leftOrNull()
                assertNotNull(error)
                assertEquals(400, error.httpCode)
                assertEquals("reactionTimeMs must be in range [1, 5000]", error.error?.message)
            }

        apiClient
            .tryRecordReactionTime(ReactionTimeApiRequest(5001))
            .also {
                assertTrue { it.isLeft() }
                val error = it.leftOrNull()
                assertNotNull(error)
                assertEquals(400, error.httpCode)
                assertEquals("reactionTimeMs must be in range [1, 5000]", error.error?.message)
            }
    }

    @Test
    fun dailyCheckIn() {
        val now = Clock.System.now()
        val telegramUserId = TelegramUserId(123L)
        val apiClient = TelegramMiniAppApiClient(telegramUserId)

        // initial checkIn on startup
        apiClient.signUp().also {
            assertEquals("20".crPoints(), it.balance)

            assertEquals(1, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(1, it.checkInStreak.gameTickets)
        }

        // 2 days
        updateUser(telegramUserId) {
            it.createdAt = now - 5.days + 1.minutes
            it.lastStreakDayGrantedAt = now - 1.days
        }
        apiClient.getUser().also {
            assertEquals("45".crPoints(), it.balance)

            assertEquals(3, it.gameTickets)

            assertEquals(2, it.checkInStreak.days)
            assertEquals("25".crPoints(), it.checkInStreak.reward)
            assertEquals(2, it.checkInStreak.gameTickets)
        }
        // idempotency
        apiClient.getUser().also {
            assertEquals("45".crPoints(), it.balance)

            assertEquals(3, it.gameTickets)

            assertEquals(2, it.checkInStreak.days)
            assertEquals("25".crPoints(), it.checkInStreak.reward)
            assertEquals(2, it.checkInStreak.gameTickets)
        }

        // 3 days
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 1.days
        }
        apiClient.getUser().also {
            assertEquals("75".crPoints(), it.balance)

            assertEquals(6, it.gameTickets)

            assertEquals(3, it.checkInStreak.days)
            assertEquals("30".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }

        // 23 hours passed - no streak bonus
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 23.hours
        }
        apiClient.getUser().also {
            assertEquals("75".crPoints(), it.balance)

            assertEquals(6, it.gameTickets)

            assertEquals(3, it.checkInStreak.days)
            assertEquals("30".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }
        // 1 hour - no streak bonus
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 1.hours
        }
        apiClient.getUser().also {
            assertEquals("75".crPoints(), it.balance)

            assertEquals(6, it.gameTickets)

            assertEquals(3, it.checkInStreak.days)
            assertEquals("30".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }

        // 4 days
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 1.days
        }
        apiClient.getUser().also {
            assertEquals("110".crPoints(), it.balance)

            assertEquals(11, it.gameTickets)

            assertEquals(4, it.checkInStreak.days)
            assertEquals("35".crPoints(), it.checkInStreak.reward)
            assertEquals(5, it.checkInStreak.gameTickets)
        }

        // streak reset
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 2.days
        }
        apiClient.getUser().also {
            assertEquals("130".crPoints(), it.balance)

            assertEquals(12, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(1, it.checkInStreak.gameTickets)
        }
    }

    @Test
    fun milestones() {
        val now = Clock.System.now()
        val telegramUserId = TelegramUserId(234L)
        val apiClient = TelegramMiniAppApiClient(telegramUserId)

        apiClient.signUp().also {
            assertEquals("20".crPoints(), it.balance)

            assertEquals(1, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(1, it.checkInStreak.gameTickets)

            assertEquals(5, it.invites)
            assertEquals("980".crPoints(), it.nextMilestoneIn)
            assertNull(it.lastMilestone)
        }

        apiClient
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.GithubSubscription))
            .also {
                assertEquals("1020".crPoints(), it.balance)
                assertEquals(8, it.invites)

                assertEquals("980".crPoints(), it.nextMilestoneIn)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        apiClient
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.MediumSubscription))
            .also {
                assertEquals("2020".crPoints(), it.balance)
                assertEquals(11, it.invites)

                assertEquals("6980".crPoints(), it.nextMilestoneIn)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // same reply when resolving user
        apiClient
            .getUser()
            .also {
                assertEquals("2020".crPoints(), it.balance)
                assertEquals(11, it.invites)

                assertEquals("6980".crPoints(), it.nextMilestoneIn)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // grant some games
        updateUser(telegramUserId) {
            it.gameTickets = 5000
        }

        // bump balance by playing game
        (5000 downTo 4000 step 10).forEach {
            apiClient.recordReactionTime(ReactionTimeApiRequest(it.toLong()))
        }
        apiClient
            .getUser()
            .also {
                assertEquals("12120".crPoints(), it.balance)
                assertEquals(14, it.invites)

                assertEquals("23880".crPoints(), it.nextMilestoneIn)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // set user balance right before the last milestone (378000) and play game
        updateUser(telegramUserId) { user ->
            TelegramMiniAppUserRewardEntity.reactionGame(user, BigDecimal("365870"))
        }
        apiClient.recordReactionTime(ReactionTimeApiRequest(100L))

        apiClient
            .getUser()
            .also {
                assertEquals("378090".crPoints(), it.balance)
                assertEquals(-1, it.invites)

                // null because the last milestone was reached already
                assertNull(it.nextMilestoneIn)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(-1, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }
    }

    private fun updateUser(telegramUserId: TelegramUserId, fn: (TelegramMiniAppUserEntity) -> Unit) {
        transaction {
            TelegramMiniAppUserEntity.findByTelegramUserId(telegramUserId)?.let { fn(it) }
        }
    }

    private fun verifyAuthFailure(expectedError: String, call: () -> Either<ApiCallFailure, Any>) {
        call().assertError(
            expectedHttpCode = HTTP_UNAUTHORIZED,
            expectedError = ApiError(ReasonCode.AuthenticationError, expectedError),
        )
    }
}
