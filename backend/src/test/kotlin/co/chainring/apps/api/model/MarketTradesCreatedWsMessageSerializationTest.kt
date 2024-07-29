package co.chainring.apps.api.model

import co.chainring.apps.api.model.websocket.MarketTradesCreated
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TradeId
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class MarketTradesCreatedWsMessageSerializationTest {
    @Test
    fun `test market trades are serialized into array`() {
        val message = MarketTradesCreated(
            MarketId("BTC/ETH"),
            listOf(
                MarketTradesCreated.Trade(
                    TradeId("t1"),
                    OrderSide.Buy,
                    amount = BigInteger.ONE,
                    price = BigDecimal.TEN,
                    timestamp = Instant.fromEpochMilliseconds(1722001494355),
                ),
            ),
        )
        val str = Json.encodeToString(message)
        assertEquals("{\"marketId\":\"BTC/ETH\",\"trades\":[[\"t1\",\"Buy\",\"1\",\"10\",1722001494355]]}", str)
        assertEquals(message, Json.decodeFromString<MarketTradesCreated>(str))
    }
}
