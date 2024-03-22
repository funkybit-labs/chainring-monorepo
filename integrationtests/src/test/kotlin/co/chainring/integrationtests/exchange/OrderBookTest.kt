package co.chainring.integrationtests.exchange

import co.chainring.apps.api.model.IncomingWSMessage
import co.chainring.apps.api.model.OrderBook
import co.chainring.apps.api.model.OutgoingWSMessage
import co.chainring.core.model.Instrument
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
class OrderBookTest {
    @Test
    fun `test order book over websocket`() {
        val connectUri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect")
        val client = WebsocketClient.blocking(connectUri)
        val message: IncomingWSMessage = IncomingWSMessage.Subscribe(Instrument("BTC/ETH"))
        client.send(WsMessage(Json.encodeToString(message)))
        val received = client.received().take(1).first()
        val decoded = Json.decodeFromString<OutgoingWSMessage>(received.bodyString())
        val orderBook = (decoded as OutgoingWSMessage.Publish).data as OrderBook
        assertEquals("BTC/ETH", orderBook.instrument.value)
        assertEquals(9, orderBook.buy.size)
        assertEquals(10, orderBook.sell.size)
        client.close()
    }
}
