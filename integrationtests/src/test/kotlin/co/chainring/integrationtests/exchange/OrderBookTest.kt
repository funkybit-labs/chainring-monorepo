package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.blocking
import co.chainring.integrationtests.testutils.subscribe
import co.chainring.integrationtests.testutils.waitForMessage
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertIs

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
        client.subscribe(SubscriptionTopic.OrderBook(MarketId("BTC/ETH")))

        client.waitForMessage().also { message ->
            assertEquals(SubscriptionTopic.OrderBook(MarketId("BTC/ETH")), message.topic)
            message.data.also { orderBook ->
                assertIs<OrderBook>(orderBook)
                assertEquals("BTC/ETH", orderBook.marketId.value)
                assertEquals(9, orderBook.buy.size)
                assertEquals(10, orderBook.sell.size)
            }
        }

        client.close()
    }
}
