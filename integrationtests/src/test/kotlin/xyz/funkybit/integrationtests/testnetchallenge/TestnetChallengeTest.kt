package xyz.funkybit.integrationtests.testnetchallenge

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.awaitility.kotlin.await
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.Card
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.toSymbolInfo
import xyz.funkybit.apps.ring.BlockchainDepositHandler
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardId
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardTable
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardType
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.utils.TestnetChallengeUtils
import xyz.funkybit.integrationtests.api.asBitcoinAddress
import xyz.funkybit.integrationtests.api.asEcKeyPair
import xyz.funkybit.integrationtests.api.asEvmAddress
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.OrderBaseTest
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertMyLimitOrderCreatedMessageReceived
import xyz.funkybit.integrationtests.utils.signAuthorizeBitcoinWalletRequest
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

data class Trader(
    val apiClient: TestApiClient,
    val wallet: Wallet,
    val wsClient: WsClient,
) {
    val a = apiClient
    val w = wallet
    val ws = wsClient
}

@ExtendWith(AppUnderTestRunner::class)
class TestnetChallengeTest : OrderBaseTest() {
    private fun enrollInTestnetChallenge(inviteCode: String? = null): Trader {
        val trader = setupTrader(
            marketId = btcUsdcMarket.id,
            airdrops = listOf(),
            deposits = listOf(),
            subscribeToOrderBook = false,
            subscribeToLimits = false,
            subscribeToPrices = false,
        ).let { Trader(it.first, it.second, it.third) }

        trader.a.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.Unenrolled, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }
        trader.a.testnetChallengeEnroll(inviteCode)
        trader.a.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.PendingAirdrop, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }
        trader.w.currentBlockchainClient().mine()

        await.pollInSameThread().atMost(
            Duration.ofSeconds(5),
        ).pollInterval(
            Duration.ofMillis(500),
        ).pollDelay(
            Duration.ofMillis(50),
        ).until {
            triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_monitor")
            trader.a.getAccountConfiguration().testnetChallengeStatus == TestnetChallengeStatus.PendingDeposit
        }

        val depositSymbol = transaction { TestnetChallengeUtils.depositSymbol() }
        val usdcDepositAmount = AssetAmount(depositSymbol.toSymbolInfo(FaucetMode.AllSymbols), "10000")
        val usdcDepositTxHash = trader.w.sendDepositTx(usdcDepositAmount)
        trader.w.currentBlockchainClient().mine()
        trader.a.createDeposit(CreateDepositApiRequest(Symbol(depositSymbol.name), usdcDepositAmount.inFundamentalUnits, usdcDepositTxHash)).deposit

        trader.a.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.PendingDepositConfirmation, it.testnetChallengeStatus)
        }
        trader.w.currentBlockchainClient().mine(BlockchainDepositHandler.DEFAULT_NUM_CONFIRMATIONS)

        waitForBalance(
            trader.a,
            trader.ws,
            listOf(
                ExpectedBalance(usdcDepositAmount),
            ),
        )
        trader.a.getAccountConfiguration().let {
            assertEquals(TestnetChallengeStatus.Enrolled, it.testnetChallengeStatus)
            assertNull(it.nickName)
            assertNull(it.avatarUrl)
        }

        return trader
    }

    @Test
    fun `testnet challenge flow`() {
        val trader = enrollInTestnetChallenge()
        trader.a.setNickname("My Nickname")
        trader.a.getAccountConfiguration().let {
            assertEquals("My Nickname", it.nickName)
            assertNull(it.avatarUrl)
        }
        // idempotent
        trader.a.setNickname("My Nickname")

        val trader2 = enrollInTestnetChallenge()
        val left = trader2.a.trySetNickname("My Nickname").leftOrNull()
        assertNotNull(left)
        assertEquals(422, left!!.httpCode)
        assertEquals("Nickname is already taken", left.error!!.message)

        // leaderboard should have both users
        val daily = trader.a.getLeaderboard(TestnetChallengePNLType.DailyPNL)
        assertEquals(1, daily.page)
        assertEquals(1, daily.lastPage)
        assertEquals(TestnetChallengePNLType.DailyPNL, daily.type)
        assertEquals(2, daily.entries.size)
        val weekly = trader.a.getLeaderboard(TestnetChallengePNLType.WeeklyPNL)
        assertEquals(1, weekly.page)
        assertEquals(1, weekly.lastPage)
        assertEquals(TestnetChallengePNLType.WeeklyPNL, weekly.type)
        val overall = trader.a.getLeaderboard(TestnetChallengePNLType.OverallPNL)
        assertEquals(1, overall.page)
        assertEquals(1, overall.lastPage)
        assertEquals(TestnetChallengePNLType.OverallPNL, overall.type)

        // cards
        var cards = trader.a.getCards()
        assertEquals(3, cards.size)
        assertEquals(
            setOf(
                Card.Enrolled,
                Card.BitcoinConnect,
                Card.EvmWithdrawal,
            ),
            cards.toSet(),
        )

        // once the user places an order, they shouldn't get the Enrolled card
        val response = trader.a.createLimitOrder(btcUsdcMarket, OrderSide.Buy, BigDecimal.ONE, BigDecimal.valueOf(25), trader.w)
        trader.ws.apply {
            assertMyLimitOrderCreatedMessageReceived(response)
        }
        cards = trader.a.getCards()
        assertEquals(2, cards.size)
        assertEquals(
            setOf(
                Card.BitcoinConnect,
                Card.EvmWithdrawal,
            ),
            cards.toSet(),
        )

        // connect a bitcoin wallet, they shouldn't get the BitcoinConnect card, but
        // should get the BitcoinWithdrawal one
        val bitcoinKeyApiClient = TestApiClient.withBitcoinWallet()
        bitcoinKeyApiClient.authorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = trader.a.keyPair.asEcKeyPair(),
                address = trader.a.address.asEvmAddress(),
                authorizedAddress = bitcoinKeyApiClient.address.asBitcoinAddress(),
            ),
        )
        cards = trader.a.getCards()
        assertEquals(2, cards.size)
        assertEquals(
            setOf(
                Card.BitcoinWithdrawal,
                Card.EvmWithdrawal,
            ),
            cards.toSet(),
        )

        // perform an evm withdrawal, they shouldn't get the EvmWithdrawal card
        val usdcWithdrawalAmount = AssetAmount(usdc, "0.001")

        trader.a.createWithdrawal(trader.w.signWithdraw(btc.name, usdcWithdrawalAmount.inFundamentalUnits))

        cards = trader.a.getCards()
        assertEquals(1, cards.size)
        assertEquals(
            setOf(
                Card.BitcoinWithdrawal,
            ),
            cards.toSet(),
        )
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

        assertEquals(BigDecimal.ZERO, apiClient.a.getAccountConfiguration().pointsBalance)

        // same day
        simulateTimeAndLastUpdate(
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")
        assertEquals(BigDecimal.ZERO, apiClient.a.getAccountConfiguration().pointsBalance)

        // day boundary crossed
        simulateTimeAndLastUpdate(
            // Sunday
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 0, minute = 30),
            // Saturday
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 7, hour = 23, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")

        // daily, 1st place
        assertEquals(BigDecimal("12500"), apiClient.a.getAccountConfiguration().pointsBalance.setScale(0))
    }

    @Test
    fun `test points distribution - weekly`() {
        val apiClient = enrollInTestnetChallenge()
        assertEquals(BigDecimal.ZERO, apiClient.a.getAccountConfiguration().pointsBalance)

        // same day
        simulateTimeAndLastUpdate(
            now = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 23, minute = 30),
            lastUpdate = LocalDateTime(year = 2024, monthNumber = 9, dayOfMonth = 8, hour = 22, minute = 30),
        )
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_leaderboard")
        assertEquals(BigDecimal.ZERO, apiClient.a.getAccountConfiguration().pointsBalance)

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
            actual = apiClient.a.getAccountConfiguration().pointsBalance.setScale(0),
        )
    }

    @Test
    fun `testnet challenge invalid invite code`() {
        enrollInTestnetChallenge(inviteCode = "does_not_throw_if_code_not_found")
    }

    @Test
    fun `test referral points`() {
        val trader1 = enrollInTestnetChallenge()
        val trader11 = enrollInTestnetChallenge(trader1.a.getAccountConfiguration().inviteCode)
        val trader12 = enrollInTestnetChallenge(trader1.a.getAccountConfiguration().inviteCode)
        val trader121 = enrollInTestnetChallenge(trader12.a.getAccountConfiguration().inviteCode)

        val trader2 = enrollInTestnetChallenge()
        val trader21 = enrollInTestnetChallenge(trader2.a.getAccountConfiguration().inviteCode)

        val trader3 = enrollInTestnetChallenge()
        val trader31 = enrollInTestnetChallenge(trader3.a.getAccountConfiguration().inviteCode)
        val trader311 = enrollInTestnetChallenge(trader31.a.getAccountConfiguration().inviteCode)
        val trader3111 = enrollInTestnetChallenge(trader311.a.getAccountConfiguration().inviteCode)

        listOf(trader1, trader11, trader12, trader121, trader2, trader21, trader3).forEach {
            assertEquals(BigDecimal.ZERO, it.a.getAccountConfiguration().pointsBalance)
        }

        // setup points
        transaction {
            grantDailyPoints(trader1, 0)
            grantDailyPoints(trader11, 100)
            grantDailyPoints(trader12, 1000)
            grantDailyPoints(trader121, 100)
            grantDailyPoints(trader2, 500)
            grantDailyPoints(trader21, 500)
            grantDailyPoints(trader3, 500)
            grantDailyPoints(trader3111, 100)
        }
        assertEquals(0, 0.toBigDecimal().compareTo(trader1.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader11.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 1000.toBigDecimal().compareTo(trader12.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader121.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader2.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader21.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader3.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader3.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 0.toBigDecimal().compareTo(trader31.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 0.toBigDecimal().compareTo(trader311.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader3111.a.getAccountConfiguration().pointsBalance))

        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_referral_points")

        assertEquals(0, (10 + 100 + 1).toBigDecimal().compareTo(trader1.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader11.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, (1000 + 10).toBigDecimal().compareTo(trader12.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader121.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, (500 + 50).toBigDecimal().compareTo(trader2.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader21.a.getAccountConfiguration().pointsBalance))

        assertEquals(0, (500 + 0).toBigDecimal().compareTo(trader3.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 1.toBigDecimal().compareTo(trader31.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 10.toBigDecimal().compareTo(trader311.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader3111.a.getAccountConfiguration().pointsBalance))

        // repeated executions have no effect
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_referral_points")
        triggerRepeaterTaskAndWaitForCompletion("testnet_challenge_referral_points")

        assertEquals(0, (10 + 100 + 1).toBigDecimal().compareTo(trader1.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader11.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, (1000 + 10).toBigDecimal().compareTo(trader12.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader121.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, (500 + 50).toBigDecimal().compareTo(trader2.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 500.toBigDecimal().compareTo(trader21.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, (500 + 0).toBigDecimal().compareTo(trader3.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 1.toBigDecimal().compareTo(trader31.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 10.toBigDecimal().compareTo(trader311.a.getAccountConfiguration().pointsBalance))
        assertEquals(0, 100.toBigDecimal().compareTo(trader3111.a.getAccountConfiguration().pointsBalance))
    }

    private fun grantDailyPoints(trader1: Trader, points: Int) = TestnetChallengeUserRewardTable.insertIgnore {
        it[guid] = EntityID(TestnetChallengeUserRewardId.generate(), TestnetChallengeUserRewardTable)
        it[userGuid] = UserEntity.findByInviteCode(trader1.a.getAccountConfiguration().inviteCode)!!.guid
        it[createdAt] = Clock.System.now()
        it[createdBy] = "test referral points"
        it[type] = TestnetChallengeUserRewardType.DailyReward
        it[amount] = points.toBigDecimal()
    }

    private fun simulateTimeAndLastUpdate(now: LocalDateTime, lastUpdate: LocalDateTime) {
        every { Clock.System.now() } returns now.toInstant(TimeZone.UTC)
        every { TestnetChallengePNLEntity.getLastUpdate() } returns lastUpdate
    }
}
