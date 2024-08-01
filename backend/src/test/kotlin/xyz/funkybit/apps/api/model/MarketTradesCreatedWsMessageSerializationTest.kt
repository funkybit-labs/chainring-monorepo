package xyz.funkybit.apps.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.funkybit.apps.api.model.websocket.MarketTradesCreated
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.TradeId
import java.math.BigDecimal
import java.math.BigInteger

class MarketTradesCreatedWsMessageSerializationTest {
    @Test
    fun `test market trades are serialized into array`() {
        val message = MarketTradesCreated(
            sequenceNumber = 123,
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
        assertEquals("{\"sequenceNumber\":123,\"marketId\":\"BTC/ETH\",\"trades\":[[\"t1\",\"Buy\",\"1\",\"10\",1722001494355]]}", str)
        assertEquals(message, Json.decodeFromString<MarketTradesCreated>(str))
    }
}
