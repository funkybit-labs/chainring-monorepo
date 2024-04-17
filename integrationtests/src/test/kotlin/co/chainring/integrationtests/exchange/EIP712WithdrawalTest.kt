package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.BalanceHelper
import co.chainring.integrationtests.testutils.ExpectedBalance
import co.chainring.integrationtests.testutils.Faucet
import co.chainring.integrationtests.testutils.Wallet
import co.chainring.integrationtests.testutils.assertError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class EIP712WithdrawalTest {

    private val logger = KotlinLogging.logger {}

    @Test
    fun testERC20EIP712Withdrawals() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        // mint some USDC
        val mintAmount = wallet.formatAmount("20", "USDC")
        wallet.mintERC20("USDC", mintAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), mintAmount)

        val depositAmount = wallet.formatAmount("15", "USDC")
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("USDC", depositAmount, depositAmount))) {
            deposit(wallet, "USDC", depositAmount)
        }
        assertEquals(wallet.getWalletERC20Balance("USDC"), mintAmount - depositAmount)

        val withdrawalAmount = wallet.formatAmount("15", "USDC")
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("USDC", depositAmount - withdrawalAmount, depositAmount - withdrawalAmount))) {
            val withdrawalApiRequest = wallet.signWithdraw("USDC", withdrawalAmount)
            val response = apiClient.createWithdrawal(withdrawalApiRequest)
            assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
            waitForFinalizedWithdrawal(response.withdrawal.id)
            assertEquals(WithdrawalStatus.Complete, apiClient.getWithdrawal(response.withdrawal.id).withdrawal.status)
        }
        assertEquals(wallet.getWalletERC20Balance("USDC"), mintAmount - depositAmount + withdrawalAmount)
    }

    @Test
    fun testNativeEIP712Withdrawals() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        val startingWalletBalance = wallet.getWalletNativeBalance()

        val depositAmount = wallet.formatAmount("0.001", "BTC")
        val depositTxReceipt = BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("BTC", depositAmount, depositAmount))) {
            deposit(wallet, "BTC", depositAmount)
        }
        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount - depositGasCost)
        // do a second one
        val depositTxReceipt2 = BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("BTC", depositAmount * BigInteger.TWO, depositAmount * BigInteger.TWO))) {
            deposit(wallet, "BTC", depositAmount)
        }
        val depositGasCost2 = depositTxReceipt2.gasUsed * Numeric.decodeQuantity(depositTxReceipt2.effectiveGasPrice)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - (depositAmount * BigInteger.TWO) - depositGasCost - depositGasCost2)

        val withdrawalAmount = wallet.formatAmount("0.001", "BTC")
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("BTC", depositAmount * BigInteger.TWO - withdrawalAmount, depositAmount * BigInteger.TWO - withdrawalAmount))) {
            val withdrawalApiRequest = wallet.signWithdraw(null, withdrawalAmount)
            val response = apiClient.createWithdrawal(withdrawalApiRequest)
            assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
            waitForFinalizedWithdrawal(response.withdrawal.id)
            assertEquals(WithdrawalStatus.Complete, apiClient.getWithdrawal(response.withdrawal.id).withdrawal.status)
            assertEquals(
                wallet.getWalletNativeBalance(),
                startingWalletBalance - (depositAmount * BigInteger.TWO) + withdrawalAmount - depositGasCost - depositGasCost2,
            )
        }
    }

    @Test
    fun testERC20EIP712Errors() {
        val apiClient = ApiClient()
        val wallet = Wallet(apiClient)
        Faucet.fund(wallet.address)

        val amount = BigInteger("1000")
        wallet.mintERC20("USDC", amount * BigInteger.TWO)
        BalanceHelper.waitForAndVerifyBalanceChange(apiClient, listOf(ExpectedBalance("USDC", total = amount, available = amount))) {
            deposit(wallet, "USDC", amount)
        }

        // invalid amount
        // TODO there is a mismatch between sequencer and contract here. Contract will fail if amount it too large,
        // but sequencer withdraws whatever is remaining - created CHAIN-85.
        var withdrawalApiRequest = wallet.signWithdraw("USDC", BigInteger("1001"))
        var response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        var withdrawal = apiClient.getWithdrawal(response.withdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, withdrawal.status)
        assertEquals("execution reverted: revert: Insufficient Balance", withdrawal.error)
        BalanceHelper.waitForAndVerifyBalanceChange(
            apiClient,
            listOf(ExpectedBalance("USDC", total = BigInteger("2000"), available = BigInteger("1000"))),
        ) {
            deposit(wallet, "USDC", amount)
        }

        // invalid nonce
        val invalidNonce = wallet.getNonce().plus(BigInteger.ONE)
        withdrawalApiRequest = wallet.signWithdraw("USDC", BigInteger("5"), invalidNonce)
        response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        withdrawal = apiClient.getWithdrawal(response.withdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, withdrawal.status)
        assertEquals("execution reverted: revert: Invalid Nonce", withdrawal.error)

        // invalid signature
        withdrawalApiRequest = wallet.signWithdraw("USDC", amount)
        // change the amount from what was signed
        apiClient.tryCreateWithdrawal(
            withdrawalApiRequest.copy(tx = withdrawalApiRequest.tx.copy(amount = BigInteger.TWO)),
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Signature not verified"),
        )
    }

    private fun deposit(wallet: Wallet, asset: String, amount: BigInteger): TransactionReceipt {
        // deposit onchain and update sequencer
        val txReceipt = if (asset == "BTC") {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
        return txReceipt
    }

    private fun waitForFinalizedWithdrawal(id: WithdrawalId) {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(30000L))
            .until {
                transaction {
                    WithdrawalEntity[id].status.isFinal()
                }
            }
    }
}
