package xyz.funkybit.integrationtests.testnetchallenge

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.toSymbolInfo
import xyz.funkybit.apps.ring.BlockchainDepositHandler
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class TestnetChallengeTest {
    private fun enrollInTestnetChallenge(): TestApiClient {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        apiClient.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.Unenrolled, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }
        apiClient.testnetChallengeEnroll()
        apiClient.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.PendingAirdrop, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }
        wallet.currentBlockchainClient().mine()

        await.pollInSameThread().atMost(
            Duration.ofSeconds(5),
        ).pollInterval(
            Duration.ofMillis(500),
        ).pollDelay(
            Duration.ofMillis(50),
        ).until {
            triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_monitor")
            apiClient.getAccountConfiguration().testnetChallengeStatus == TestnetChallengeStatus.PendingDeposit
        }

        val depositSymbol = transaction { TestnetChallengeUtils.depositSymbol() }
        val usdcDepositAmount = AssetAmount(depositSymbol.toSymbolInfo(FaucetMode.AllSymbols), "10000")
        val usdcDepositTxHash = wallet.sendDepositTx(usdcDepositAmount)
        wallet.currentBlockchainClient().mine()
        apiClient.createDeposit(CreateDepositApiRequest(Symbol(depositSymbol.name), usdcDepositAmount.inFundamentalUnits, usdcDepositTxHash)).deposit

        apiClient.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.PendingDepositConfirmation, it.testnetChallengeStatus)
        }
        wallet.currentBlockchainClient().mine(BlockchainDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(usdcDepositAmount),
            ),
        )
        apiClient.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.Enrolled, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }

        return apiClient
    }

    @Test
    fun `testnet challenge flow`() {
        val apiClient = enrollInTestnetChallenge()
        apiClient.setNickname("My Nickname")
        apiClient.getAccountConfiguration().let {
            assertEquals("My Nickname", it.nickName)
            assertNull(it.avatarUrl)
        }
        // idempotent
        apiClient.setNickname("My Nickname")

        val apiClient2 = enrollInTestnetChallenge()
        val left = apiClient2.trySetNickname("My Nickname").leftOrNull()
        assertNotNull(left)
        assertEquals(422, left!!.httpCode)
        assertEquals("Nickname is already taken", left.error!!.message)

        // leaderboard should have both users
        val daily = apiClient.getLeaderboard(TestnetChallengePNLType.DailyPNL)
        assertEquals(1, daily.page)
        assertEquals(1, daily.lastPage)
        assertEquals(TestnetChallengePNLType.DailyPNL, daily.type)
        assertEquals(2, daily.entries.size)
        val weekly = apiClient.getLeaderboard(TestnetChallengePNLType.WeeklyPNL)
        assertEquals(1, weekly.page)
        assertEquals(1, weekly.lastPage)
        assertEquals(TestnetChallengePNLType.WeeklyPNL, weekly.type)
        val overall = apiClient.getLeaderboard(TestnetChallengePNLType.OverallPNL)
        assertEquals(1, overall.page)
        assertEquals(1, overall.lastPage)
        assertEquals(TestnetChallengePNLType.OverallPNL, overall.type)
    }

    @BeforeTest
    fun setUp() {
        // mock Clock and last updated
        mockkObject(Clock.System)
        mockkObject(TestnetChallengePNLEntity)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test points distribution - daily`() {
        val apiClient = enrollInTestnetChallenge()

        assertEquals(BigDecimal.ZERO, apiClient.getAccountConfiguration().pointsBalance)

        // same day
        simulateTimeAndLastUpdate(
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")
        assertEquals(BigDecimal.ZERO, apiClient.getAccountConfiguration().pointsBalance)

        // day boundary crossed
        simulateTimeAndLastUpdate(
            // Sunday
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 0, minute = 30),
            // Saturday
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")

        // daily, 1st place
        assertEquals(BigDecimal("12500"), apiClient.getAccountConfiguration().pointsBalance.setScale(0))
    }

    @Test
    fun `test points distribution - weekly`() {
        val apiClient = enrollInTestnetChallenge()
        assertEquals(BigDecimal.ZERO, apiClient.getAccountConfiguration().pointsBalance)

        // same day
        simulateTimeAndLastUpdate(
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 23, minute = 30),
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 22, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")
        assertEquals(BigDecimal.ZERO, apiClient.getAccountConfiguration().pointsBalance)

        // day + week boundaries crossed
        simulateTimeAndLastUpdate(
            // Monday
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 9, hour = 0, minute = 30),
            // Sunday
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 23, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")

        // daily + weekly, 1st place
        assertEquals(
            expected = BigDecimal("12500") + BigDecimal("50000"),
            actual = apiClient.getAccountConfiguration().pointsBalance.setScale(0),
        )
    }

    private fun simulateTimeAndLastUpdate(now: LocalDateTime, lastUpdate: LocalDateTime) {
        every { Clock.System.now() } returns now.toInstant(TimeZone.UTC)
        every { TestnetChallengePNLEntity.getLastUpdate() } returns lastUpdate
    }
}
