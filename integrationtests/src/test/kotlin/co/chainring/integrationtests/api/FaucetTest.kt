package co.chainring.integrationtests.api

import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.core.model.TxHash
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.Faucet
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

        wallet.getWalletNativeBalance()
        val config = apiClient.getConfiguration()

        assertEquals(config.chains.size, 2)

        config.chains.forEach { chain ->
            wallet.switchChain(chain.id)

            val nativeBalanceBefore = wallet.getWalletNativeBalance()
            apiClient.faucet(FaucetApiRequest(chainId = chain.id, wallet.address)).also {
                assertEquals(BigInteger("100000000000000000"), it.amount)
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
                    Faucet.mine(chainId = chain.id)
                    val nativeBalanceAfter = wallet.getWalletNativeBalance()
                    assertEquals(BigInteger("100000000000000000"), nativeBalanceAfter - nativeBalanceBefore)
                }
        }
    }
}
