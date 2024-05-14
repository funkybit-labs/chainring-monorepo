package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ListWithdrawalsApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.ExchangeTransactionStatus
import co.chainring.core.model.db.ExchangeTransactionTable
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.testutils.waitForFinalizedWithdrawal
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertError
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class WithdrawalTest {
    @Test
    fun withdrawals() {
        val apiClient = TestApiClient()
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
        val depositTxReceipt = wallet.depositNative(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount, available = btcDepositAmount),
            ),
        )

        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getWalletNativeBalance(), walletStartingBtcBalance - btcDepositAmount - depositGasCost)

        // deposit more BTC
        val depositTxReceipt2 = wallet.depositNative(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO, available = btcDepositAmount * BigInteger.TWO),
            ),
        )

        val depositGasCost2 = depositTxReceipt2.gasUsed * Numeric.decodeQuantity(depositTxReceipt2.effectiveGasPrice)
        assertEquals(wallet.getWalletNativeBalance(), walletStartingBtcBalance - (btcDepositAmount * BigInteger.TWO) - depositGasCost - depositGasCost2)

        // deposit some USDC
        val usdcDepositAmount = wallet.formatAmount("15", "USDC")
        wallet.depositERC20("USDC", usdcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO, available = btcDepositAmount * BigInteger.TWO),
                ExpectedBalance("USDC", total = usdcDepositAmount, available = usdcDepositAmount),
            ),
        )
        assertEquals(wallet.getWalletERC20Balance("USDC"), usdcMintAmount - usdcDepositAmount)

        val walletBtcBalanceBeforeWithdrawals = wallet.getWalletNativeBalance()

        // withdraw some BTC
        val btcWithdrawalAmount = wallet.formatAmount("0.001", "BTC")

        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw("BTC", btcWithdrawalAmount)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(pendingBtcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount, available = usdcDepositAmount),
            ),
        )

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(btcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount, available = usdcDepositAmount),
            ),
        )
        assertEquals(
            walletBtcBalanceBeforeWithdrawals + btcWithdrawalAmount,
            wallet.getWalletNativeBalance(),
        )

        // withdraw some USDC
        val usdcWithdrawalAmount = wallet.formatAmount("14", "USDC")

        val pendingUsdcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw("USDC", usdcWithdrawalAmount)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(pendingUsdcWithdrawal, btcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount, available = usdcDepositAmount - usdcWithdrawalAmount),
            ),
        )

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)

        val usdcWithdrawal = apiClient.getWithdrawal(pendingUsdcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, usdcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(usdcWithdrawal, btcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount - usdcWithdrawalAmount, available = usdcDepositAmount - usdcWithdrawalAmount),
            ),
        )
        assertEquals(wallet.getWalletERC20Balance("USDC"), usdcMintAmount - usdcDepositAmount + usdcWithdrawalAmount)

        // when requested withdrawal amount > remaining amount, whatever is remaining is withdrawn
        val pendingUsdcWithdrawal2 = apiClient.createWithdrawal(wallet.signWithdraw("USDC", usdcDepositAmount - usdcWithdrawalAmount + BigInteger.ONE)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal2.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = usdcDepositAmount - usdcWithdrawalAmount, available = BigInteger.ZERO),
            ),
        )

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal2.id)

        val usdcWithdrawal2 = apiClient.getWithdrawal(pendingUsdcWithdrawal2.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, usdcWithdrawal2.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount, available = btcDepositAmount * BigInteger.TWO - btcWithdrawalAmount),
                ExpectedBalance("USDC", total = BigInteger.ZERO, available = BigInteger.ZERO),
            ),
        )
    }

    @Test
    fun `withdrawal errors`() {
        val apiClient = TestApiClient()
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        val amount = BigInteger("1000")
        wallet.mintERC20("USDC", amount * BigInteger.TWO)

        wallet.depositERC20("USDC", amount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("USDC", total = amount, available = amount),
            ),
        )

        // invalid signature
        apiClient.tryCreateWithdrawal(
            wallet.signWithdraw("USDC", amount).copy(amount = BigInteger.TWO),
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
    }

    @Test
    fun `withdrawal blockchain failure`() {
        val apiClient = TestApiClient()
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        // deposit some BTC
        val btcDepositAmount = wallet.formatAmount("0.002", "BTC")
        wallet.depositNative(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount, available = btcDepositAmount),
            ),
        )

        // withdraw some BTC
        val btcWithdrawalAmount = wallet.formatAmount("0.001", "BTC")

        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw("BTC", btcWithdrawalAmount)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(pendingBtcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount, available = btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
        assertEquals(ListWithdrawalsApiResponse(listOf(btcWithdrawal)), apiClient.listWithdrawals())

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount - btcWithdrawalAmount, available = btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        // the only reason withdrawals should fail is a signature failure (which should not happen since
        // Exchange API service verifies signatures before sending on chain). To simulate this
        // and verify the sequencer rollbacks, we will modify the withdrawal signature of an already submitted tx and
        // resubmit it on chain - this should fail and trigger the rollback in the sequencer and
        // available balance should go back to what it was before.
        transaction {
            WithdrawalEntity[pendingBtcWithdrawal.id].status = WithdrawalStatus.Pending
            val withdrawalExchangeTransaction = ExchangeTransactionEntity.all()
                .orderBy(ExchangeTransactionTable.sequenceId to SortOrder.DESC)
                .limit(1).first()
            // resign with a different nonce so signature should fail
            val signature = wallet.signWithdraw("BTC", btcWithdrawalAmount).signature
            withdrawalExchangeTransaction.transactionData = (withdrawalExchangeTransaction.transactionData as EIP712Transaction.WithdrawTx).copy(
                signature = signature,
            )
            withdrawalExchangeTransaction.status = ExchangeTransactionStatus.Pending
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal2 = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, btcWithdrawal2.status)
        assertEquals("Invalid Signature", btcWithdrawal2.error)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance("BTC", total = btcDepositAmount - btcWithdrawalAmount, available = btcDepositAmount),
            ),
        )
    }
}
