package xyz.funkybit.integrationtests.exchange

import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.ApiClient
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertOrderBookMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToOrderBook

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
