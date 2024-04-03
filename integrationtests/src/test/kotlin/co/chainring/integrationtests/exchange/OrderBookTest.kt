package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribe
import co.chainring.integrationtests.testutils.waitForMessage
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class OrderBookTest {
    @Test
    fun `test order book over websocket`() {
        val client = WebsocketClient.blocking()
        client.subscribe(SubscriptionTopic.OrderBook(MarketId("BTC/ETH")))

        val orderBook = client.waitForMessage<OrderBook>()
        assertEquals("BTC/ETH", orderBook.marketId.value)
        assertEquals(9, orderBook.buy.size)
        assertEquals(10, orderBook.sell.size)

        client.close()
    }
}
