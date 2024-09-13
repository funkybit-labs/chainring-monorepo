package xyz.funkybit.integrationtests.testnetchallenge

import org.awaitility.kotlin.await
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.CardType
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.toSymbolInfo
import xyz.funkybit.apps.ring.BlockchainDepositHandler
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeStatus
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
    private fun enrollInTestnetChallenge(): Trader {
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
        trader.a.testnetChallengeEnroll()
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
                CardType.Enrolled,
                CardType.BitcoinConnect,
                CardType.EvmWithdrawal,
            ),
            cards.map { it.type }.toSet(),
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
                CardType.BitcoinConnect,
                CardType.EvmWithdrawal,
            ),
            cards.map { it.type }.toSet(),
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
                CardType.BitcoinWithdrawal,
                CardType.EvmWithdrawal,
            ),
            cards.map { it.type }.toSet(),
        )

        // perform an evm withdrawal, they shouldn't get the EvmWithdrawal card
        val usdcWithdrawalAmount = AssetAmount(usdc, "0.001")

        trader.a.createWithdrawal(trader.w.signWithdraw(btc.name, usdcWithdrawalAmount.inFundamentalUnits))

        cards = trader.a.getCards()
        assertEquals(1, cards.size)
        assertEquals(
            setOf(
                CardType.BitcoinWithdrawal,
            ),
            cards.map { it.type }.toSet(),
        )
    }
}
