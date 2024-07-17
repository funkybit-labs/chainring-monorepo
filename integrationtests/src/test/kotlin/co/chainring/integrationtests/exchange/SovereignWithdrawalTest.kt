package co.chainring.integrationtests.exchange

import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToBalances
import co.chainring.tasks.blockchainClient
import co.chainring.tasks.fixtures.toChainSymbol
import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class SovereignWithdrawalTest {

    @Test
    fun testSovereignWithdrawalSequenced() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        Assertions.assertEquals(config.chains.size, 2)

        (0 until config.chains.size).forEach { index ->
            wallet.switchChain(config.chains[index].id)
            Faucet.fundAndMine(wallet.address, amount = BigDecimal("1").toFundamentalUnits(18), chainId = wallet.currentChainId)

            val btc = config.chains[index].symbols.first { it.name == "BTC".toChainSymbol(config.chains[index].id) }
            val usdc = config.chains[index].symbols.first { it.name == "USDC".toChainSymbol(config.chains[index].id) }
            Assertions.assertEquals(BigDecimal("0.00002").toFundamentalUnits(btc.decimals.toInt()), btc.withdrawalFee)
            Assertions.assertEquals(BigDecimal("1").toFundamentalUnits(usdc.decimals.toInt()), usdc.withdrawalFee)
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            Assertions.assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            // deposit some BTC
            val btcDepositAmount = AssetAmount(btc, "0.5")
            val depositTxReceipt = wallet.depositAndMine(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val depositGasCost = AssetAmount(btc, depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice))
            Assertions.assertEquals(walletStartingBtcBalance - btcDepositAmount - depositGasCost, wallet.getWalletBalance(btc))

            // deposit some USDC
            val usdcDepositAmount = AssetAmount(usdc, "16")
            wallet.depositAndMine(usdcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            Assertions.assertEquals(usdcMintAmount - usdcDepositAmount, wallet.getWalletBalance(usdc))

            // BTC sovereign withdrawal
            val walletBtcBalanceBeforeWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
            val btcWithdrawalAmount = BigDecimal("0.1")
            val btcWithdrawalTxHash = wallet.requestSovereignWithdrawalAndMine(btc.name, btcWithdrawalAmount.toFundamentalUnits(btc.decimals))
            val btcWithdrawalGasCost = blockchainClient(wallet.currentChainId).gasUsed(btcWithdrawalTxHash)!!
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
                    val withdrawalAmount = btcWithdrawalAmount.toFundamentalUnits(btc.decimals)
                    val withdrawalFee = btc.withdrawalFee

                    // calculate also gas since gas is paid in native symbol
                    assertEquals(
                        expected = walletBtcBalanceBeforeWithdrawal + withdrawalAmount - withdrawalFee - btcWithdrawalGasCost,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())

            // USDC sovereign withdrawal
            val walletUsdcBalanceBeforeWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
            val usdcWithdrawalAmount = BigDecimal("10")
            wallet.requestSovereignWithdrawalAndMine(usdc.name, usdcWithdrawalAmount.toFundamentalUnits(usdc.decimals))
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
                    val withdrawalAmount = usdcWithdrawalAmount.toFundamentalUnits(usdc.decimals)
                    val withdrawalFee = usdc.withdrawalFee

                    assertEquals(
                        expected = walletUsdcBalanceBeforeWithdrawal + withdrawalAmount - withdrawalFee,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())
        }
    }
}
