package co.chainring.core.ohlc

import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OHLCEntity
import co.chainring.testutils.TestWithDb
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

class OHLCDurationTest : TestWithDb() {

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(123UL), "test-chain")
            val btc = createNativeSymbol("BTC", chain.id.value, decimals = 18U)
            val eth = createSymbol("ETH", chain.id.value, decimals = 18U)
            val usdc = createSymbol("USDC", chain.id.value, decimals = 18U)
            createMarket(btc, eth, tickSize = "0.05".toBigDecimal()).id.value
            createMarket(eth, usdc, tickSize = "0.01".toBigDecimal()).id.value
        }
    }

    @Test
    fun `OHLC period start is calculated correctly`() {
        val instant = Instant.parse("2024-04-20T07:58:55.789155Z")

        assertEquals(Instant.parse("2024-04-20T07:58:00Z"), OHLCDuration.P1M.durationStart(instant))
        assertEquals(Instant.parse("2024-04-20T07:55:00Z"), OHLCDuration.P5M.durationStart(instant))
        assertEquals(Instant.parse("2024-04-20T07:45:00Z"), OHLCDuration.P15M.durationStart(instant))
        assertEquals(Instant.parse("2024-04-20T07:00:00Z"), OHLCDuration.P1H.durationStart(instant))
        assertEquals(Instant.parse("2024-04-20T04:00:00Z"), OHLCDuration.P4H.durationStart(instant))
        assertEquals(Instant.parse("2024-04-20T00:00:00Z"), OHLCDuration.P1D.durationStart(instant))
    }

    @Test
    fun `OHLC periods are updated`() {
        transaction {
            // setup
            val market = MarketEntity.all().first().guid.value

            val now = Instant.parse("2024-04-20T07:51:56Z")
            val nowNextSecond = Instant.parse("2024-04-20T07:51:57Z")
            val nowNextNextSecond = Instant.parse("2024-04-20T07:51:58Z")
            val nextMinute = Instant.parse("2024-04-20T07:52:57Z")
            val nextFiveMinutes = Instant.parse("2024-04-20T07:57:57Z")

            val lowerTradePrice = BigDecimal("4")
            val tradePrice = BigDecimal("5")
            val higherTradePrice = BigDecimal("6")

            val tradeAmount = BigInteger("100")

            // empty initial state
            assertEquals(emptySet(), OHLCEntity.all().toSet())

            // initial records are created
            OHLCEntity.updateWith(
                market = market,
                tradeTimestamp = now,
                tradePrice = tradePrice,
                tradeAmount = tradeAmount,
            )

            assertEquals(
                expected = OHLCDuration.entries.map {
                    OHLC(
                        start = it.durationStart(now),
                        open = tradePrice.toDouble(),
                        high = tradePrice.toDouble(),
                        low = tradePrice.toDouble(),
                        close = tradePrice.toDouble(),
                        durationMs = it.durationMs(),
                    )
                }.toSet(),
                actual = OHLCEntity.all().toSet().map { it.toWSResponse() }.toSet(),
            )

            // next second (same minute) update with higher price
            OHLCEntity.updateWith(
                market = market,
                tradeTimestamp = nowNextSecond,
                tradePrice = higherTradePrice,
                tradeAmount = tradeAmount,
            )

            assertEquals(
                expected = OHLCDuration.entries.map {
                    OHLC(
                        start = it.durationStart(now),
                        open = tradePrice.toDouble(),
                        high = higherTradePrice.toDouble(),
                        low = tradePrice.toDouble(),
                        close = higherTradePrice.toDouble(),
                        durationMs = it.durationMs(),
                    )
                }.toSet(),
                actual = OHLCEntity.all().map { it.toWSResponse() }.toSet(),
            )
            OHLCEntity.all().forEach {
                assertEquals(tradeAmount.multiply(2.toBigInteger()), it.volume.toBigInteger())
            }

            // next second (same minute) update with lower price
            OHLCEntity.updateWith(
                market = market,
                tradeTimestamp = nowNextNextSecond,
                tradePrice = lowerTradePrice,
                tradeAmount = tradeAmount,
            )

            assertEquals(
                expected = OHLCDuration.entries.map {
                    OHLC(
                        start = it.durationStart(now),
                        open = tradePrice.toDouble(),
                        high = higherTradePrice.toDouble(),
                        low = lowerTradePrice.toDouble(),
                        close = lowerTradePrice.toDouble(),
                        durationMs = it.durationMs(),
                    )
                }.toSet(),
                actual = OHLCEntity.all().map { it.toWSResponse() }.toSet(),
            )
            OHLCEntity.all().forEach {
                assertEquals(tradeAmount.multiply(3.toBigInteger()), it.volume.toBigInteger())
            }

            // next minute update
            OHLCEntity.updateWith(
                market = market,
                tradeTimestamp = nextMinute,
                tradePrice = lowerTradePrice,
                tradeAmount = tradeAmount,
            )
            assertEquals(
                expected = OHLCDuration.entries.map {
                    when (it) {
                        OHLCDuration.P1M -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = lowerTradePrice.toDouble(),
                                high = lowerTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                        )

                        else -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                        )
                    }
                }.flatten().toSet(),
                actual = OHLCEntity.all().map { it.toWSResponse() }.toSet(),
            )

            // next five minutes update
            OHLCEntity.updateWith(
                market = market,
                tradeTimestamp = nextFiveMinutes,
                tradePrice = higherTradePrice,
                tradeAmount = tradeAmount,
            )
            assertEquals(
                expected = OHLCDuration.entries.map {
                    when (it) {
                        OHLCDuration.P1M -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = lowerTradePrice.toDouble(),
                                high = lowerTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                            OHLC(
                                start = it.durationStart(nextFiveMinutes),
                                open = higherTradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = higherTradePrice.toDouble(),
                                close = higherTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                        )

                        OHLCDuration.P5M -> setOf(
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = tradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = lowerTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                            OHLC(
                                start = it.durationStart(nextFiveMinutes),
                                open = higherTradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = higherTradePrice.toDouble(),
                                close = higherTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                        )

                        else -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice.toDouble(),
                                high = higherTradePrice.toDouble(),
                                low = lowerTradePrice.toDouble(),
                                close = higherTradePrice.toDouble(),
                                durationMs = it.durationMs(),
                            ),
                        )
                    }
                }.flatten().toSet(),
                actual = OHLCEntity.all().map { it.toWSResponse() }.toSet(),
            )
        }
    }
}
