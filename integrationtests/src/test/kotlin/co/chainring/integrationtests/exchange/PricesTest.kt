package co.chainring.integrationtests.exchange

import co.chainring.core.client.rest.ApiClient
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.assertPricesMessageReceived
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
        client.subscribeToPrices(MarketId("BTC/ETH"), duration = OHLCDuration.P15M)

        client.assertPricesMessageReceived(MarketId("BTC/ETH"), duration = OHLCDuration.P15M) { msg ->
            assertEquals("BTC/ETH", msg.market.value)
        }

        client.close()
    }
}
