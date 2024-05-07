package co.chainring.integrationtests.api

import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.core.model.Symbol
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.ApiClient
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToBalances
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
    fun deposits() {
        val apiClient = ApiClient()
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        // mint some USDC
        val usdcMintAmount = wallet.formatAmount("20", "USDC")
        wallet.mintERC20("USDC", usdcMintAmount)

        assertEquals(wallet.getWalletERC20Balance("USDC"), usdcMintAmount)

        val walletStartingBtcBalance = wallet.getWalletNativeBalance()

        // deposit some BTC
        val btcDepositAmount = wallet.formatAmount("0.001", "BTC")
        val btcDepositTxHash = wallet.asyncDepositNative(btcDepositAmount)
        assertTrue(apiClient.listDeposits().deposits.isEmpty())
        val pendingBtcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol("BTC"), btcDepositAmount, btcDepositTxHash)).deposit
        assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

        assertEquals(listOf(pendingBtcDeposit), apiClient.listDeposits().deposits)

        wallet.blockchainClient.mine()

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount, available = btcDepositAmount),
            ),
        )

        val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
        assertEquals(Deposit.Status.Complete, btcDeposit.status)

        assertEquals(listOf(btcDeposit), apiClient.listDeposits().deposits)

        val depositGasCost = wallet.blockchainClient.getTransactionReceipt(btcDepositTxHash).let { receipt ->
            assertNotNull(receipt)
            receipt.gasUsed * Numeric.decodeQuantity(receipt.effectiveGasPrice)
        }
        assertEquals(wallet.getWalletNativeBalance(), walletStartingBtcBalance - btcDepositAmount - depositGasCost)

        // deposit some USDC
        val usdcDepositAmount = wallet.formatAmount("15", "USDC")
        val usdcDepositTxHash = wallet.asyncDepositERC20("USDC", usdcDepositAmount)

        assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == "USDC" })
        val pendingUsdcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol("USDC"), usdcDepositAmount, usdcDepositTxHash)).deposit
        assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

        assertEquals(listOf(pendingUsdcDeposit, btcDeposit), apiClient.listDeposits().deposits)

        wallet.blockchainClient.mine()

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount, available = btcDepositAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount, available = usdcDepositAmount),
            ),
        )

        val usdcDeposit = apiClient.getDeposit(pendingUsdcDeposit.id).deposit
        assertEquals(Deposit.Status.Complete, usdcDeposit.status)

        assertEquals(listOf(usdcDeposit, btcDeposit), apiClient.listDeposits().deposits)

        assertEquals(wallet.getWalletERC20Balance("USDC"), usdcMintAmount - usdcDepositAmount)
    }
}
