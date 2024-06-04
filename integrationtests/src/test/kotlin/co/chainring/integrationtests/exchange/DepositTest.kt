package co.chainring.integrationtests.exchange

import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.deposit
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.ECKeyPair
import org.web3j.utils.Numeric
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class DepositTest {

    private val walletPrivateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97"

    @Test
    fun testERC20Deposits() {
        val apiClient = TestApiClient(ECKeyPair.create(walletPrivateKeyHex.toHexBytes()))
        val wallet = Wallet(apiClient)
        val symbolInfo = wallet.chains.first { it.id == wallet.currentChainId }.symbols.first { it.name == "USDC" }

        // mint some USDC
        val startingUsdcWalletBalance = wallet.getWalletERC20Balance("USDC")
        val mintAmount = BigDecimal("20").toFundamentalUnits(symbolInfo.decimals.toInt())
        wallet.mintERC20("USDC", mintAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount)

        val startingUsdcExchangeBalance = wallet.getExchangeERC20Balance("USDC")
        val depositAmount = BigDecimal("15").toFundamentalUnits(symbolInfo.decimals.toInt())

        deposit(wallet, apiClient, AssetAmount(symbolInfo, depositAmount))
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount)
    }

    @Test
    fun testNativeDeposits() {
        val apiClient = TestApiClient(ECKeyPair.create(walletPrivateKeyHex.toHexBytes()))
        val wallet = Wallet(apiClient)
        val symbolInfo = wallet.chains.first { it.id == wallet.currentChainId }.symbols.first { it.contractAddress == null }

        val startingWalletBalance = wallet.getWalletNativeBalance()
        val startingExchangeBalance = wallet.getExchangeNativeBalance()
        val depositAmount = BigDecimal("2").toFundamentalUnits(symbolInfo.decimals.toInt())

        val depositTxReceipt = deposit(wallet, apiClient, AssetAmount(symbolInfo, depositAmount))
        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount - depositGasCost)
    }
}
