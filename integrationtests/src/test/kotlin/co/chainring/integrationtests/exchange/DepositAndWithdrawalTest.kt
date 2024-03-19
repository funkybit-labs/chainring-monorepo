package co.chainring.integrationtests.exchange

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import co.chainring.core.model.db.Chain
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.TestWalletKeypair
import co.chainring.integrationtests.testutils.Wallet
import co.chainring.integrationtests.testutils.toFundamentalUnits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class DepositAndWithdrawalTest {

    private val walletKeypair = TestWalletKeypair(
        "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
        Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
    )

    @Test
    fun testConfiguration() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration()
        assertEquals(config.contracts.size, 1)
        assertEquals(config.contracts[0].name, ContractType.Exchange.name)
        assertEquals(config.contracts[0].chain, Chain.Ethereum)
        val client = BlockchainClient().loadExchangeContract(config.contracts[0].address)
        assertEquals(client.version.send().toInt(), 1)
        val usdcToken = config.erc20Tokens.firstOrNull { it.symbol.value == "USDC" }
        assertNotNull(usdcToken)

        val nativeToken = config.nativeTokens.firstOrNull { it.chain == Chain.Ethereum }
        assertNotNull(nativeToken)
        assertEquals("ETH", nativeToken!!.symbol)
        assertEquals(18.toUByte(), nativeToken.decimals)
    }

    @Test
    fun testERC20DepositsAndWithdrawals() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration()
        val wallet = Wallet(walletKeypair, config.contracts, config.erc20Tokens)
        val decimals = config.erc20Tokens.first { it.symbol.value == "USDC" }.decimals.toInt()

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
        wallet.withdrawERC20("USDC", withdrawalAmount)
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount + withdrawalAmount)
    }

    @Test
    fun testNativeDepositsAndWithdrawals() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration()
        val wallet = Wallet(walletKeypair, config.contracts, config.erc20Tokens)
        val decimals = config.nativeTokens.first { it.chain == Chain.Ethereum }.decimals.toInt()

        val startingWalletBalance = wallet.getWalletNativeBalance()
        val startingExchangeBalance = wallet.getExchangeNativeBalance()
        val depositAmount = BigDecimal("2").toFundamentalUnits(decimals)

        val depositTxReceipt = wallet.depositNative(depositAmount)
        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount - depositGasCost)

        val withdrawalAmount = BigDecimal("2").toFundamentalUnits(decimals)
        val withdrawalTxReceipt = wallet.withdrawNative(withdrawalAmount)
        val withdrawalGasCost = withdrawalTxReceipt.gasUsed * Numeric.decodeQuantity(withdrawalTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount + withdrawalAmount - depositGasCost - withdrawalGasCost)
    }
}
