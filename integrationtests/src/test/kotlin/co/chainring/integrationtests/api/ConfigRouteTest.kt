package co.chainring.integrationtests.api

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.FeeRate
import co.chainring.core.model.db.FeeRates
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.tasks.fixtures.toChainSymbol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class ConfigRouteTest {
    @Test
    fun testConfiguration() {
        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)
        ChainManager.blockchainConfigs.forEachIndexed { index, clientConfig ->
            val client = BlockchainClient(clientConfig)
            val chainConfig = config.chains.first { it.id == client.chainId }
            assertEquals(chainConfig.contracts.size, 1)
            assertEquals(chainConfig.contracts[0].name, ContractType.Exchange.name)
            val exchangeContract = client.loadExchangeContract(chainConfig.contracts[0].address)
            assertEquals(exchangeContract.version.send().toInt(), 1)

            assertNotNull(chainConfig.symbols.firstOrNull { it.name == "ETH".toChainSymbol(index) })
            assertNotNull(chainConfig.symbols.firstOrNull { it.name == "USDC".toChainSymbol(index) })

            val nativeToken = chainConfig.symbols.first { it.contractAddress == null }
            assertEquals("BTC".toChainSymbol(index), nativeToken.name)
            assertEquals(18.toUByte(), nativeToken.decimals)
        }

        assertEquals(
            FeeRates(maker = FeeRate.fromPercents(0.01), taker = FeeRate.fromPercents(0.02)),
            config.feeRates,
        )
    }
}
