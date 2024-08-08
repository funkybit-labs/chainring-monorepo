package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createMarket
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createSymbol
import xyz.funkybit.testutils.TestWithDb
import java.math.BigDecimal
import java.math.BigInteger

class TradeEntityTest : TestWithDb() {
    private val btcEthMarket = MarketId("BTC:123/ETH:123")

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(123UL), "test-chain")
            val btc = createNativeSymbol("BTC", chain.id.value, decimals = 18U)
            val eth = createSymbol("ETH", chain.id.value, decimals = 18U)
            createMarket(btc, eth, tickSize = "0.05".toBigDecimal(), lastPrice = "17.525".toBigDecimal()).id.value
        }
    }

    @Test
    fun `find pending trades for new settlement batch`() {
        assertEquals(
            emptyList<String>(),
            getPendingTradeGuidsForNewSettlementBatch(limit = 5),
        )

        createTrades(
            listOf(
                Trade(TradeId("trade_1"), btcEthMarket, SettlementStatus.Completed, responseSequence = 8),
                Trade(TradeId("trade_2"), btcEthMarket, SettlementStatus.Settling, responseSequence = 9),
            ),
        )
        assertEquals(
            emptyList<String>(),
            getPendingTradeGuidsForNewSettlementBatch(limit = 5),
        )

        createTrades(
            listOf(
                Trade(TradeId("trade_3"), btcEthMarket, SettlementStatus.Pending, responseSequence = 10),
                Trade(TradeId("trade_4"), btcEthMarket, SettlementStatus.Pending, responseSequence = 10),
                Trade(TradeId("trade_5"), btcEthMarket, SettlementStatus.Pending, responseSequence = 10),
                Trade(TradeId("trade_6"), btcEthMarket, SettlementStatus.Pending, responseSequence = 11),
                Trade(TradeId("trade_7"), btcEthMarket, SettlementStatus.Pending, responseSequence = 11),
                Trade(TradeId("trade_8"), btcEthMarket, SettlementStatus.Pending, responseSequence = 12),
            ),
        )

        assertEquals(
            listOf("trade_3", "trade_4", "trade_5", "trade_6", "trade_7"),
            getPendingTradeGuidsForNewSettlementBatch(limit = 5),
        )

        assertEquals(
            listOf("trade_3", "trade_4", "trade_5", "trade_6", "trade_7", "trade_8"),
            getPendingTradeGuidsForNewSettlementBatch(limit = 6),
        )

        assertEquals(
            listOf("trade_3", "trade_4", "trade_5", "trade_6", "trade_7"),
            getPendingTradeGuidsForNewSettlementBatch(limit = 4),
        )

        assertEquals(
            listOf("trade_3", "trade_4", "trade_5"),
            getPendingTradeGuidsForNewSettlementBatch(limit = 2),
        )

        assertEquals(
            listOf("trade_3", "trade_4", "trade_5", "trade_6", "trade_7", "trade_8"),
            getPendingTradeGuidsForNewSettlementBatch(limit = 10),
        )
    }

    data class Trade(
        val id: TradeId,
        val market: MarketId,
        val status: SettlementStatus,
        val responseSequence: Long,
    )

    private fun createTrades(trades: List<Trade>) {
        transaction {
            val now = Clock.System.now()
            trades.forEach { trade ->
                val tradeMarket = MarketEntity[trade.market]

                TradeEntity.create(
                    now,
                    tradeMarket,
                    amount = BigInteger.ONE,
                    price = BigDecimal.TEN,
                    tradeHash = generateHexString(32),
                    trade.responseSequence,
                    id = trade.id,
                ).also {
                    it.settlementStatus = trade.status
                }
            }
        }
    }

    private fun getPendingTradeGuidsForNewSettlementBatch(limit: Int): List<String> =
        transaction {
            TradeEntity.findPendingForNewSettlementBatch(limit).map { it.guid.value.value }.toList()
        }
}
