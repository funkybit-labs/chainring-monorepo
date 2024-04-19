package co.chainring.integrationtests.exchange

import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.assertOrderBookMessageReceived
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribeToOrderBook
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class OrderBookTest {
    @Test
    fun `test public order book over websocket`() {
        `test order book over websocket`(auth = null)
    }

    @Test
    fun `test authenticated order book over websocket`() {
        `test order book over websocket`(auth = ApiClient.issueAuthToken())
    }

    private fun `test order book over websocket`(auth: String?) {
        val client = WebsocketClient.blocking(auth)
        client.subscribeToOrderBook(MarketId("BTC/ETH"))

        client.assertOrderBookMessageReceived(MarketId("BTC/ETH")) { msg ->
            assertEquals("BTC/ETH", msg.marketId.value)
        }

        client.close()
    }
}
