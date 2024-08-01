package xyz.funkybit.apps.api.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.core.model.db.MarketId
import java.math.BigInteger

class LimitsWsMessageSerializationTest {
    @Test
    fun `test market limits are serialized into array`() {
        val message = Limits(listOf(MarketLimits(MarketId("BTC/ETH"), BigInteger.ONE, BigInteger.TWO)))
        val str = Json.encodeToString(message)
        assertEquals("{\"limits\":[[\"BTC/ETH\",\"1\",\"2\"]]}", str)
        assertEquals(message, Json.decodeFromString<Limits>(str))
    }
}
