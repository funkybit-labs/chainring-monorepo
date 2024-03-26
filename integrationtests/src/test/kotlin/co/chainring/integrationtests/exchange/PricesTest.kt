package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.IncomingWSMessage
import co.chainring.apps.api.model.OutgoingWSMessage
import co.chainring.apps.api.model.Prices
import co.chainring.apps.api.model.SubscriptionTopic
import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.apiServerRootUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.WsMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class PricesTest {
    @Test
    fun `test prices over websocket`() {
        val connectUri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect")
        val client = WebsocketClient.blocking(connectUri)
        val message: IncomingWSMessage = IncomingWSMessage.Subscribe(MarketId("ETH/USDC"), SubscriptionTopic.Prices)
        client.send(WsMessage(Json.encodeToString(message)))
        val received = client.received().take(1).first()
        val decoded = Json.decodeFromString<OutgoingWSMessage>(received.bodyString())
        val prices = (decoded as OutgoingWSMessage.Publish).data as Prices
        assertEquals("ETH/USDC", prices.market.value)
        assertEquals(12 * 24 * 7, prices.ohlc.size)
        client.close()
    }
}
