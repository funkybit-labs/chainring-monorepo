package co.chainring.integrationtests.api

import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.model.Symbol
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.tasks.fixtures.toChainSymbol
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertNotNull

@ExtendWith(AppUnderTestRunner::class)
class DepositTest {
    @Test
    fun `deposits multiple chains`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)

        (0 until config.chains.size).forEach { index ->

            wallet.switchChain(config.chains[index].id)
            Faucet.fund(wallet.address, chainId = wallet.currentChainId)

            val btcSymbol = "BTC".toChainSymbol(index)
            val usdcSymbol = "USDC".toChainSymbol(index)
            val symbolFilterList = listOf(btcSymbol, usdcSymbol)

            // mint some USDC
            val usdcMintAmount = wallet.formatAmount("20", usdcSymbol)
            wallet.mintERC20(usdcSymbol, usdcMintAmount)

            assertEquals(wallet.getWalletERC20Balance(usdcSymbol), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletNativeBalance()

            val btcDepositAmount = wallet.formatAmount("0.001", btcSymbol)
            val usdcDepositAmount = wallet.formatAmount("15", usdcSymbol)

            val btcDepositTxHash = wallet.asyncDepositNative(btcDepositAmount)
            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == btcSymbol })
            val pendingBtcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(btcSymbol), btcDepositAmount, btcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingBtcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btcSymbol })

            wallet.currentBlockchainClient().mine()

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcSymbol, total = btcDepositAmount, available = btcDepositAmount),
                ),
            )

            val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, btcDeposit.status)
            assertEquals(listOf(btcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btcSymbol })

            val depositGasCost = wallet.currentBlockchainClient().getTransactionReceipt(btcDepositTxHash).let { receipt ->
                assertNotNull(receipt)
                receipt.gasUsed * Numeric.decodeQuantity(receipt.effectiveGasPrice)
            }
            assertEquals(wallet.getWalletNativeBalance(), walletStartingBtcBalance - btcDepositAmount - depositGasCost)

            // deposit some USDC
            val usdcDepositTxHash = wallet.asyncDepositERC20(usdcSymbol, usdcDepositAmount)

            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == usdcSymbol })
            val pendingUsdcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(usdcSymbol), usdcDepositAmount, usdcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingUsdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            wallet.currentBlockchainClient().mine()

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcSymbol, total = btcDepositAmount, available = btcDepositAmount),
                    ExpectedBalance(usdcSymbol, total = usdcDepositAmount, available = usdcDepositAmount),
                ),
            )

            val usdcDeposit = apiClient.getDeposit(pendingUsdcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, usdcDeposit.status)

            assertEquals(listOf(usdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            assertEquals(wallet.getWalletERC20Balance(usdcSymbol), usdcMintAmount - usdcDepositAmount)
        }
    }
}
