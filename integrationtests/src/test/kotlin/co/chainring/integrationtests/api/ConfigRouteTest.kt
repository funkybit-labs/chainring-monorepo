package co.chainring.integrationtests.api

import co.chainring.apps.api.model.FeeRatesInBps
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
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
        val chainConfig = config.chains.first()
        assertEquals(chainConfig.contracts.size, 1)
        assertEquals(chainConfig.contracts[0].name, ContractType.Exchange.name)
        val client = BlockchainClient().loadExchangeContract(chainConfig.contracts[0].address)
        assertEquals(client.version.send().toInt(), 1)

        assertNotNull(chainConfig.symbols.firstOrNull { it.name == "ETH" })
        assertNotNull(chainConfig.symbols.firstOrNull { it.name == "USDC" })

        val nativeToken = chainConfig.symbols.first { it.contractAddress == null }
        assertEquals("BTC", nativeToken.name)
        assertEquals(18.toUByte(), nativeToken.decimals)

        assertEquals(FeeRatesInBps(maker = 100, taker = 200), config.feeRatesInBps)
    }
}
