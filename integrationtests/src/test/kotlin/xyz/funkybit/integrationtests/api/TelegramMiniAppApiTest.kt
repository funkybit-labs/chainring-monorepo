package xyz.funkybit.integrationtests.api

import arrow.core.Either
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.http4k.core.toUrlFormEncoded
import org.http4k.format.KotlinxSerialization.json
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.middleware.TelegramMiniAppUserData
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.tma.ClaimRewardApiRequest
import xyz.funkybit.apps.api.model.tma.GetUserApiResponse
import xyz.funkybit.apps.api.model.tma.ReactionTimeApiRequest
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGoal
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserIsBot
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardEntity
import xyz.funkybit.core.utils.crPoints
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.utils.ApiCallFailure
import xyz.funkybit.integrationtests.utils.TelegramMiniAppApiClient
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.integrationtests.utils.empty
import java.math.BigDecimal
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
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
            TelegramMiniAppApiClient.tryGetUser(false) { Headers.empty }
        }

        verifyAuthFailure("Invalid authentication scheme") {
            TelegramMiniAppApiClient.tryGetUser(false) { mapOf("Authorization" to "signature token").toHeaders() }
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

            TelegramMiniAppApiClient.tryGetUser(false) { mapOf("Authorization" to "Bearer $token").toHeaders() }
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

            TelegramMiniAppApiClient.tryGetUser(false) { mapOf("Authorization" to "Bearer $token").toHeaders() }
        }
    }

    @Test
    fun simultaneousSignup() {
        (1..20).forEach {
            val succeeded = AtomicInteger(0)
            val userId = Random.nextLong()
            val thread1 = thread(start = false) {
                TelegramMiniAppApiClient(TelegramUserId(userId)).signUp()
                succeeded.incrementAndGet()
            }
            val thread2 = thread(start = false) {
                TelegramMiniAppApiClient(TelegramUserId(userId)).signUp()
                succeeded.incrementAndGet()
            }
            thread1.start()
            thread2.start()
            thread1.join()
            thread2.join()
            assertEquals(2, succeeded.get())
        }
    }

    @Test
    fun signup() {
        val apiClient = TelegramMiniAppApiClient(TelegramUserId(123L))

        val result = apiClient.signUp().also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, "0".crPoints().compareTo(it.referralBalance))

            assertEquals(3, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)

            assertEquals(5, it.invites)
            assertEquals("1000".crPoints(), it.nextMilestoneAt)
            assertNull(it.lastMilestone)

            assertEquals(
                listOf(
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.GithubSubscription,
                        reward = "240".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.DiscordSubscription,
                        reward = "240".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.LinkedinSubscription,
                        reward = "240".crPoints(),
                        achieved = false,
                    ),
                    GetUserApiResponse.Goal(
                        TelegramMiniAppGoal.Id.XSubscription,
                        reward = "240".crPoints(),
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
                assertEquals("260".crPoints(), it.balance)
                assertEquals(
                    listOf(
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.GithubSubscription,
                            reward = "240".crPoints(),
                            achieved = true,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.DiscordSubscription,
                            reward = "240".crPoints(),
                            achieved = false,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.LinkedinSubscription,
                            reward = "240".crPoints(),
                            achieved = false,
                        ),
                        GetUserApiResponse.Goal(
                            TelegramMiniAppGoal.Id.XSubscription,
                            reward = "240".crPoints(),
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
                assertEquals("260".crPoints(), it.balance)
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
        assertEquals(3, apiClient.getUser().gameTickets)
        assertEquals("20".crPoints(), apiClient.getUser().balance)

        apiClient
            .recordReactionTime(ReactionTimeApiRequest(200))
            .also {
                assertEquals("70".crPoints(), it.balance)
                assertEquals(50, it.percentile)
                assertEquals(0, "50".crPoints().compareTo(it.reward))
            }
        // play two more times without earning points
        apiClient
            .recordReactionTime(ReactionTimeApiRequest(2000))
        apiClient
            .recordReactionTime(ReactionTimeApiRequest(3000))

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

            assertEquals(3, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }

        // 2 days
        updateUser(telegramUserId) {
            it.createdAt = now - 5.days + 1.minutes
            it.lastStreakDayGrantedAt = now - 1.days
        }
        apiClient.getUser().also {
            assertEquals("45".crPoints(), it.balance)

            assertEquals(6, it.gameTickets)

            assertEquals(2, it.checkInStreak.days)
            assertEquals("25".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }
        // idempotency
        apiClient.getUser().also {
            assertEquals("45".crPoints(), it.balance)

            assertEquals(6, it.gameTickets)

            assertEquals(2, it.checkInStreak.days)
            assertEquals("25".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }

        // 3 days
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 1.days
        }
        apiClient.getUser().also {
            assertEquals("75".crPoints(), it.balance)

            assertEquals(9, it.gameTickets)

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

            assertEquals(9, it.gameTickets)

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

            assertEquals(9, it.gameTickets)

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

            assertEquals(14, it.gameTickets)

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

            assertEquals(17, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }

        // one more streak reset
        updateUser(telegramUserId) {
            it.lastStreakDayGrantedAt = now - 2.days
        }
        apiClient.getUser().also {
            assertEquals("150".crPoints(), it.balance)

            assertEquals(20, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)
        }
    }

    @Test
    fun milestones() {
        val now = Clock.System.now()
        val telegramUserId = TelegramUserId(234L)
        val apiClient = TelegramMiniAppApiClient(telegramUserId)

        apiClient.signUp().also {
            assertEquals("20".crPoints(), it.balance)

            assertEquals(3, it.gameTickets)

            assertEquals(1, it.checkInStreak.days)
            assertEquals("20".crPoints(), it.checkInStreak.reward)
            assertEquals(3, it.checkInStreak.gameTickets)

            assertEquals(5, it.invites)
            assertEquals("1000".crPoints(), it.nextMilestoneAt)
            assertNull(it.lastMilestone)
        }

        // grant some games
        updateUser(telegramUserId) {
            it.gameTickets = 5000
        }

        // bump balance by playing game
        (5000 downTo 4910 step 10).forEach {
            apiClient.recordReactionTime(ReactionTimeApiRequest(it.toLong()))
        }
        apiClient.getUser()
            .also {
                assertEquals("1020".crPoints(), it.balance)
                assertEquals(8, it.invites)

                assertEquals("2000".crPoints(), it.nextMilestoneAt)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // bump balance by playing game
        (4900 downTo 4810 step 10).forEach {
            apiClient.recordReactionTime(ReactionTimeApiRequest(it.toLong()))
        }
        apiClient.getUser()
            .also {
                assertEquals("2020".crPoints(), it.balance)
                assertEquals(11, it.invites)

                assertEquals("9000".crPoints(), it.nextMilestoneAt)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(3, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // bump balance by playing game
        (4800 downTo 3800 step 10).forEach {
            apiClient.recordReactionTime(ReactionTimeApiRequest(it.toLong()))
        }
        apiClient
            .getUser()
            .also {
                assertEquals("12120".crPoints(), it.balance)
                assertEquals(22, it.invites)

                assertEquals("36000".crPoints(), it.nextMilestoneAt)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(11, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }

        // set user balance right before the last milestone (378000) and play game
        updateUser(telegramUserId) { user ->
            TelegramMiniAppUserRewardEntity.createReactionGameReward(user, BigDecimal("365870"))
        }
        apiClient.recordReactionTime(ReactionTimeApiRequest(100L))

        apiClient
            .getUser()
            .also {
                assertEquals("378090".crPoints(), it.balance)
                assertEquals(-1, it.invites)

                // null because the last milestone was reached already
                assertNull(it.nextMilestoneAt)

                val lastMilestone = it.lastMilestone
                assertNotNull(lastMilestone)
                assertEquals(-1, lastMilestone.invites)
                assertTrue { lastMilestone.grantedAt > now }
            }
    }

    @Test
    fun referralPoints() {
        // setup
        val lZeroInvitee = TelegramMiniAppApiClient(TelegramUserId(1110L))
        val lZeroInviteCode = lZeroInvitee.signUp().also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }.inviteCode

        val lOneInvitee = TelegramMiniAppApiClient(TelegramUserId(1111L))
        val lOneInviteCode = lOneInvitee.signUp(inviteCode = lZeroInviteCode).also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }.inviteCode

        val lTwoInvitee = TelegramMiniAppApiClient(TelegramUserId(1112L))
        val lTwoInviteCode = lTwoInvitee.signUp(inviteCode = lOneInviteCode).also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }.inviteCode

        val lThreeInvitee = TelegramMiniAppApiClient(TelegramUserId(1113L))
        lThreeInvitee.signUp(inviteCode = lTwoInviteCode).also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }.inviteCode

        val justUser = TelegramMiniAppApiClient(TelegramUserId(1119L))
        justUser.signUp(inviteCode = null).also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }

        lThreeInvitee
            .claimReward(ClaimRewardApiRequest(TelegramMiniAppGoal.Id.GithubSubscription))
            .also {
                assertEquals("260".crPoints(), it.balance)
                assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
            }

        // distribute referral rewards (3->2)
        triggerRepeaterTaskAndWaitForCompletion("referral_points")

        lZeroInvitee.getUser().also {
            assertEquals("22".crPoints(), it.balance)
            assertEquals("2".crPoints(), it.referralBalance)
        }
        lOneInvitee.getUser().also {
            assertEquals("22".crPoints(), it.balance)
            assertEquals("2".crPoints(), it.referralBalance)
        }
        lTwoInvitee.getUser().also {
            assertEquals("46".crPoints(), it.balance)
            assertEquals("26".crPoints(), it.referralBalance)
        }
        lThreeInvitee.getUser().also {
            assertEquals("260".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }

        // distribute referral rewards (2->1)
        triggerRepeaterTaskAndWaitForCompletion("referral_points")

        lZeroInvitee.getUser().also {
            assertEquals("22.2".crPoints(), it.balance)
            assertEquals("2.2".crPoints(), it.referralBalance)
        }
        lOneInvitee.getUser().also {
            assertEquals("24.6".crPoints(), it.balance)
            assertEquals("4.6".crPoints(), it.referralBalance)
        }
        lTwoInvitee.getUser().also {
            assertEquals("46".crPoints(), it.balance)
            assertEquals("26".crPoints(), it.referralBalance)
        }
        lThreeInvitee.getUser().also {
            assertEquals("260".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }

        // distribute referral rewards (1->0)
        triggerRepeaterTaskAndWaitForCompletion("referral_points")

        lZeroInvitee.getUser().also {
            assertEquals("22.46".crPoints(), it.balance)
            assertEquals("2.46".crPoints(), it.referralBalance)
        }
        lOneInvitee.getUser().also {
            assertEquals("24.6".crPoints(), it.balance)
            assertEquals("4.6".crPoints(), it.referralBalance)
        }
        lTwoInvitee.getUser().also {
            assertEquals("46".crPoints(), it.balance)
            assertEquals("26".crPoints(), it.referralBalance)
        }
        lThreeInvitee.getUser().also {
            assertEquals("260".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }

        // distribute referral rewards (no changes)
        triggerRepeaterTaskAndWaitForCompletion("referral_points")

        lZeroInvitee.getUser().also {
            assertEquals("22.46".crPoints(), it.balance)
            assertEquals("2.46".crPoints(), it.referralBalance)
        }
        lOneInvitee.getUser().also {
            assertEquals("24.6".crPoints(), it.balance)
            assertEquals("4.6".crPoints(), it.referralBalance)
        }
        lTwoInvitee.getUser().also {
            assertEquals("46".crPoints(), it.balance)
            assertEquals("26".crPoints(), it.referralBalance)
        }
        lThreeInvitee.getUser().also {
            assertEquals("260".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }
        justUser.getUser().also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }

        updateUser(lThreeInvitee.telegramUserId) { user ->
            user.isBot = TelegramMiniAppUserIsBot.Yes
            TelegramMiniAppUserRewardEntity.createReactionGameReward(user, BigDecimal("280"))
        }
        assertEquals("540".crPoints(), lThreeInvitee.getUser().balance)

        // distribute referral rewards (no one should get referral points from lThreeInvitee)
        triggerRepeaterTaskAndWaitForCompletion("referral_points")

        lZeroInvitee.getUser().also {
            assertEquals("22.46".crPoints(), it.balance)
            assertEquals("2.46".crPoints(), it.referralBalance)
        }
        lOneInvitee.getUser().also {
            assertEquals("24.6".crPoints(), it.balance)
            assertEquals("4.6".crPoints(), it.referralBalance)
        }
        lTwoInvitee.getUser().also {
            assertEquals("46".crPoints(), it.balance)
            assertEquals("26".crPoints(), it.referralBalance)
        }
        lThreeInvitee.getUser().also {
            assertEquals("540".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
        }
        justUser.getUser().also {
            assertEquals("20".crPoints(), it.balance)
            assertEquals(0, it.referralBalance.compareTo("0".crPoints()))
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
