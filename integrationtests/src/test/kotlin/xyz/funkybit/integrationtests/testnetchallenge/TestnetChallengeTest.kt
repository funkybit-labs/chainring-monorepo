package xyz.funkybit.integrationtests.testnetchallenge

import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.toSymbolInfo
import xyz.funkybit.apps.ring.BlockchainDepositHandler
import xyz.funkybit.core.model.Symbol
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
import java.time.Duration

@ExtendWith(AppUnderTestRunner::class)
class TestnetChallengeTest {
    @Test
    fun `testnet challenge flow`() {
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
        }
    }
}