package xyz.funkybit.integrationtests.exchange

import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.ApiClient
import xyz.funkybit.integrationtests.utils.assertPricesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToPrices

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
