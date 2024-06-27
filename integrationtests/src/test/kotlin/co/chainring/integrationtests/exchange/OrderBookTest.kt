package co.chainring.integrationtests.exchange

import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.ApiClient
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToOrderBook
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
        val apiClient = TestApiClient()
        val config = apiClient.getConfiguration()
        val chain1 = config.chains.first().id
        client.subscribeToOrderBook(MarketId("BTC:$chain1/ETH:$chain1"))

        client.assertOrderBookMessageReceived(MarketId("BTC:$chain1/ETH:$chain1")) { msg ->
            assertEquals("BTC:$chain1/ETH:$chain1", msg.marketId.value)
        }

        client.close()
    }
}
