package co.chainring.integrationtests.api

import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainId
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.AssetAmount
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
class FaucetTest {
    @Test
    fun `receive native tokens using faucet`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        wallet.getWalletNativeBalance()
        val config = apiClient.getConfiguration()

        assertEquals(config.chains.size, 2)

        config.chains.forEachIndexed { index, chain ->

            apiClient.faucet(FaucetApiRequest(chainId = chain.id, wallet.address))


            wallet.switchChain(config.chains[index].id)

            Faucet.fund(wallet.address, chainId = wallet.currentChainId)

            val btc = config.chains[index].symbols.first { it.name == "BTC".toChainSymbol(index) }

        }
    }
}
