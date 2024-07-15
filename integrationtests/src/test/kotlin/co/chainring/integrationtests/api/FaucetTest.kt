package co.chainring.integrationtests.api

import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotEquals

@ExtendWith(AppUnderTestRunner::class)
class FaucetTest {
    @Test
    fun `receive native tokens using faucet`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val config = apiClient.getConfiguration()

        assertEquals(config.chains.size, 2)

        config.chains.forEach { chain ->
            wallet.switchChain(chain.id)
            val nativeSymbol = Symbol(chain.symbols.first { it.contractAddress == null }.name)

            val nativeBalanceBefore = wallet.getWalletNativeBalance()
            apiClient.faucet(FaucetApiRequest(nativeSymbol, wallet.address)).also {
                assertEquals(nativeSymbol, it.symbol)
                assertEquals(BigInteger("1000000000000000000"), it.amount)
                assertEquals(chain.id, it.chainId)
                assertNotEquals(TxHash.emptyHash(), it.txHash)
            }

            await
                .withAlias("Waiting for block confirmation")
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(10000L))
                .untilAsserted {
                    wallet.currentBlockchainClient().mine()
                    val nativeBalanceAfter = wallet.getWalletNativeBalance()
                    assertEquals(BigInteger("1000000000000000000"), nativeBalanceAfter - nativeBalanceBefore)
                }
        }
    }

    @Test
    fun `receive ERC20 tokens using faucet`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val config = apiClient.getConfiguration()

        assertEquals(config.chains.size, 2)

        config.chains.forEach { chain ->
            wallet.switchChain(chain.id)
            val erc20Symbol = Symbol(chain.symbols.first { it.contractAddress != null && it.name.startsWith("ETH") }.name)

            val balanceBefore = wallet.getWalletERC20Balance(erc20Symbol)
            apiClient.faucet(FaucetApiRequest(erc20Symbol, wallet.address)).also {
                assertEquals(erc20Symbol, it.symbol)
                assertEquals(BigInteger("1000000000000000000"), it.amount)
                assertEquals(chain.id, it.chainId)
                assertNotEquals(TxHash.emptyHash(), it.txHash)
            }

            await
                .withAlias("Waiting for block confirmation")
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(10000L))
                .untilAsserted {
                    wallet.currentBlockchainClient().mine()
                    val balanceAfter = wallet.getWalletERC20Balance(erc20Symbol)
                    assertEquals(BigInteger("1000000000000000000"), balanceAfter - balanceBefore)
                }
        }
    }
}
