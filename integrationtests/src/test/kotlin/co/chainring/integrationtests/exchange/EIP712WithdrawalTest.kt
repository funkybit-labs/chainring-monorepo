package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.model.Address
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.integrationtests.testutils.AbnormalApiResponseException
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.TestBlockchainClient
import co.chainring.integrationtests.testutils.TestWalletKeypair
import co.chainring.integrationtests.testutils.Wallet
import co.chainring.integrationtests.testutils.apiError
import co.chainring.integrationtests.testutils.toFundamentalUnits
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class EIP712WithdrawalTest {

    private val walletKeypair = TestWalletKeypair(
        "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
        Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
    )

    private val blockchainClient = TestBlockchainClient(BlockchainClientConfig().copy(privateKeyHex = walletKeypair.privateKeyHex))

    @Test
    fun testERC20EIP712Withdrawals() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration().chains.find { it.id == blockchainClient.chainId }!!
        val wallet = Wallet(blockchainClient, walletKeypair, config.contracts, config.symbols)
        val decimals = config.symbols.first { it.name == "USDC" }.decimals.toInt()

        // mint some USDC
        val startingUsdcWalletBalance = wallet.getWalletERC20Balance("USDC")
        val mintAmount = BigDecimal("20").toFundamentalUnits(decimals)
        wallet.mintERC20("USDC", mintAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount)

        val startingUsdcExchangeBalance = wallet.getExchangeERC20Balance("USDC")
        val depositAmount = BigDecimal("15").toFundamentalUnits(decimals)

        wallet.depositERC20("USDC", depositAmount)
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount)

        val withdrawalAmount = BigDecimal("12").toFundamentalUnits(decimals)
        val withdrawalApiRequest = wallet.signWithdraw("USDC", withdrawalAmount)
        val response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        assertEquals(WithdrawalStatus.Complete, apiClient.getWithdrawal(response.withdrawal.id).withdrawal.status)
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount + withdrawalAmount)
    }

    @Test
    fun testNativeEIP712Withdrawals() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration().chains.find { it.id == blockchainClient.chainId }!!
        val wallet = Wallet(blockchainClient, walletKeypair, config.contracts, config.symbols)
        val decimals = config.symbols.first { it.contractAddress == null }.decimals.toInt()

        val startingWalletBalance = wallet.getWalletNativeBalance()
        val startingExchangeBalance = wallet.getExchangeNativeBalance()
        val depositAmount = BigDecimal("2").toFundamentalUnits(decimals)

        val depositTxReceipt = wallet.depositNative(depositAmount)
        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount - depositGasCost)

        val withdrawalAmount = BigDecimal("2").toFundamentalUnits(decimals)
        val withdrawalApiRequest = wallet.signWithdraw(null, withdrawalAmount)
        val response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        assertEquals(WithdrawalStatus.Complete, apiClient.getWithdrawal(response.withdrawal.id).withdrawal.status)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount + withdrawalAmount - depositGasCost)
    }

    @Test
    fun testERC20EIP712Errors() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration().chains.find { it.id == blockchainClient.chainId }!!
        val wallet = Wallet(blockchainClient, walletKeypair, config.contracts, config.symbols)

        val startingUsdcExchangeBalance = wallet.getExchangeERC20Balance("USDC")

        // invalid amount
        var withdrawalApiRequest = wallet.signWithdraw("USDC", startingUsdcExchangeBalance + BigInteger("1000"))
        var response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        var withdrawal = apiClient.getWithdrawal(response.withdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, withdrawal.status)
        assertEquals("execution reverted: revert: Insufficient Balance", withdrawal.error)

        // invalid nonce
        val invalidNonce = wallet.getNonce().plus(BigInteger.ONE)
        withdrawalApiRequest = wallet.signWithdraw("USDC", startingUsdcExchangeBalance, invalidNonce)
        response = apiClient.createWithdrawal(withdrawalApiRequest)
        assertEquals(WithdrawalStatus.Pending, response.withdrawal.status)
        waitForFinalizedWithdrawal(response.withdrawal.id)
        withdrawal = apiClient.getWithdrawal(response.withdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, withdrawal.status)
        assertEquals("execution reverted: revert: Invalid Nonce", withdrawal.error)

        // invalid signature
        withdrawalApiRequest = wallet.signWithdraw("USDC", startingUsdcExchangeBalance)
        assertThrows<AbnormalApiResponseException> {
            // change the amount from what was signed
            apiClient.createWithdrawal(withdrawalApiRequest.copy(tx = withdrawalApiRequest.tx.copy(amount = startingUsdcExchangeBalance + BigInteger("1000"))))
        }.also {
            assertEquals(
                ApiError(ReasonCode.SignatureNotValid, "Signature not verified"),
                it.response.apiError(),
            )
        }
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
