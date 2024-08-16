package xyz.funkybit.core.ohlc

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.apps.api.model.websocket.OHLC
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OHLCEntity
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createMarket
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createSymbol
import xyz.funkybit.testutils.TestWithDb
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
            createMarket(btc, eth, tickSize = "0.05".toBigDecimal(), lastPrice = "17.525".toBigDecimal()).id.value
            createMarket(eth, usdc, tickSize = "0.01".toBigDecimal(), lastPrice = "2999.995".toBigDecimal()).id.value
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
                        open = tradePrice,
                        high = tradePrice,
                        low = tradePrice,
                        close = tradePrice,
                        duration = it,
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
                        open = tradePrice,
                        high = higherTradePrice,
                        low = tradePrice,
                        close = higherTradePrice,
                        duration = it,
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
                        open = tradePrice,
                        high = higherTradePrice,
                        low = lowerTradePrice,
                        close = lowerTradePrice,
                        duration = it,
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
                                open = tradePrice,
                                high = higherTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
                            ),
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = lowerTradePrice,
                                high = lowerTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
                            ),
                        )

                        else -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice,
                                high = higherTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
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
                                open = tradePrice,
                                high = higherTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
                            ),
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = lowerTradePrice,
                                high = lowerTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
                            ),
                            OHLC(
                                start = it.durationStart(nextFiveMinutes),
                                open = higherTradePrice,
                                high = higherTradePrice,
                                low = higherTradePrice,
                                close = higherTradePrice,
                                duration = it,
                            ),
                        )

                        OHLCDuration.P5M -> setOf(
                            OHLC(
                                start = it.durationStart(nextMinute),
                                open = tradePrice,
                                high = higherTradePrice,
                                low = lowerTradePrice,
                                close = lowerTradePrice,
                                duration = it,
                            ),
                            OHLC(
                                start = it.durationStart(nextFiveMinutes),
                                open = higherTradePrice,
                                high = higherTradePrice,
                                low = higherTradePrice,
                                close = higherTradePrice,
                                duration = it,
                            ),
                        )

                        else -> setOf(
                            OHLC(
                                start = it.durationStart(now),
                                open = tradePrice,
                                high = higherTradePrice,
                                low = lowerTradePrice,
                                close = higherTradePrice,
                                duration = it,
                            ),
                        )
                    }
                }.flatten().toSet(),
                actual = OHLCEntity.all().map { it.toWSResponse() }.toSet(),
            )
        }
    }
}
