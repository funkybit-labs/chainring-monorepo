package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.websocket.Prices
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
class PricesTest {
    @Test
    fun `test prices over websocket`() {
        val client = WebsocketClient.blocking()
        client.subscribe(SubscriptionTopic.Prices(MarketId("BTC/ETH")))

        val prices = client.waitForMessage<Prices>()
        assertEquals("BTC/ETH", prices.market.value)
        assertEquals(12 * 24 * 7, prices.ohlc.size)

        client.close()
    }
}
