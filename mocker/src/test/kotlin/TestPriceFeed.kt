import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.mocker.core.PriceFeed
import java.time.Duration

class TestPriceFeed {
    @Test
    fun `test price feed`() {
        val btcEth = MarketId("BTC:123/ETH:234")
        val ethUsdc = MarketId("ETH:123/USDC:234")
        val btcUsdc = MarketId("BTC:123/USDC:234")

        var latestPrices: Map<MarketId, Double> = mapOf()
        fun onPriceUpdate(prices: Map<MarketId, Double>) {
            latestPrices = prices
        }

        val priceFeed = PriceFeed(listOf(btcEth, ethUsdc, btcUsdc), ::onPriceUpdate)

        priceFeed.start()
        await.pollInSameThread().atMost(Duration.ofMillis(3000L)).until {
            (latestPrices[btcEth] ?: 0.0) > 0.0 &&
                (latestPrices[ethUsdc] ?: 0.0) > 0.0 &&
                (latestPrices[btcUsdc] ?: 0.0) > 0.0
        }
        priceFeed.stop()
    }
}