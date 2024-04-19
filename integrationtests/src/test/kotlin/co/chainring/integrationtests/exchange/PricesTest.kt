package co.chainring.integrationtests.exchange

import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.assertPricesMessageReceived
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribeToPrices
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class PricesTest {
    @Test
    fun `test public prices over websocket`() {
        `test authenticated prices over websocket`(auth = null)
    }

    @Test
    fun `test authenticated prices over websocket`() {
        `test authenticated prices over websocket`(auth = ApiClient.issueAuthToken())
    }

    private fun `test authenticated prices over websocket`(auth: String?) {
        val client = WebsocketClient.blocking(auth)
        client.subscribeToPrices(MarketId("BTC/ETH"))

        client.assertPricesMessageReceived(MarketId("BTC/ETH")) { msg ->
            assertEquals("BTC/ETH", msg.market.value)
            assertEquals(12 * 24 * 7, msg.ohlc.size)
        }

        client.close()
    }
}
