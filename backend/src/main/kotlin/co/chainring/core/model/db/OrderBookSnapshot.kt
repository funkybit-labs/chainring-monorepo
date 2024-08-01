package co.chainring.core.model.db

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.utils.fromFundamentalUnits
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.upsert
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class OrderBookSnapshot(
    val bids: List<Entry>,
    val asks: List<Entry>,
    val last: LastTrade,
) {
    @Serializable
    data class Entry(
        val price: BigDecimalJson,
        val size: BigDecimalJson,
    )

    @Serializable
    data class LastTrade(
        val price: BigDecimalJson,
        val direction: LastTradeDirection,
    )

    @Serializable
    enum class LastTradeDirection {
        Up,
        Down,
        Unchanged,
    }

    data class Diff(
        val bids: List<Entry>,
        val asks: List<Entry>,
        val last: LastTrade?,
    )

    companion object {
        fun empty(market: MarketEntity): OrderBookSnapshot =
            OrderBookSnapshot(
                bids = emptyList(),
                asks = emptyList(),
                last = LastTrade(
                    BigDecimal.ZERO.setScale(market.priceScale()),
                    LastTradeDirection.Unchanged,
                ),
            )

        fun get(marketId: MarketId): OrderBookSnapshot =
            get(MarketEntity[marketId])

        fun get(market: MarketEntity): OrderBookSnapshot =
            OrderBookSnapshotTable
                .selectAll()
                .where { OrderBookSnapshotTable.marketGuid.eq(market.id) }
                .limit(1)
                .map { row ->
                    row[OrderBookSnapshotTable.orderBook]
                }
                .singleOrNull() ?: empty(market)

        fun calculate(market: MarketEntity): OrderBookSnapshot {
            val priceScale = market.priceScale()

            fun getOrderBookEntries(side: OrderSide): List<Entry> {
                val sizeCol = OrderTable.amount.sum().alias("size")

                return OrderTable
                    .select(OrderTable.price, sizeCol)
                    .where { OrderTable.marketGuid.eq(market.guid) }
                    .andWhere { OrderTable.type.eq(OrderType.Limit) }
                    .andWhere { OrderTable.side.eq(side) }
                    .andWhere { OrderTable.status.inList(listOf(OrderStatus.Open, OrderStatus.Partial)) }
                    .andWhere { OrderTable.price.isNotNull() }
                    .groupBy(OrderTable.price)
                    .orderBy(OrderTable.price, SortOrder.DESC)
                    .toList()
                    .mapNotNull {
                        val price = it[OrderTable.price] ?: return@mapNotNull null
                        val size = it[sizeCol] ?: return@mapNotNull null
                        Entry(
                            price = price.setScale(priceScale),
                            size = size.toBigInteger().fromFundamentalUnits(market.baseSymbol.decimals).stripTrailingZeros(),
                        )
                    }
            }

            // We calculate last trade's price as a size-weighted average
            // of all execution prices from the last match
            val weightedAveragePriceCol = TradeTable.price.times(TradeTable.amount).sum().div(TradeTable.amount.sum())

            val (lastTradePrice, prevTradePrice) = OrderExecutionTable
                .join(OrderTable, JoinType.LEFT, OrderExecutionTable.orderGuid, OrderTable.guid)
                .leftJoin(TradeTable)
                .select(weightedAveragePriceCol)
                .where { OrderTable.marketGuid.eq(market.guid) }
                .andWhere { OrderTable.type.eq(OrderType.Market) }
                .groupBy(
                    OrderExecutionTable.orderGuid,
                    OrderExecutionTable.timestamp,
                )
                .orderBy(OrderExecutionTable.timestamp, SortOrder.DESC)
                .limit(2)
                .mapNotNull { it[weightedAveragePriceCol] }
                .let {
                    Pair(
                        it.getOrElse(0) { BigDecimal.ZERO },
                        it.getOrElse(1) { BigDecimal.ZERO },
                    )
                }

            return OrderBookSnapshot(
                bids = getOrderBookEntries(OrderSide.Buy),
                asks = getOrderBookEntries(OrderSide.Sell),
                last = LastTrade(
                    price = lastTradePrice.setScale(priceScale, RoundingMode.HALF_EVEN),
                    direction = when {
                        lastTradePrice > prevTradePrice -> LastTradeDirection.Up
                        lastTradePrice < prevTradePrice -> LastTradeDirection.Down
                        else -> LastTradeDirection.Unchanged
                    },
                ),
            )
        }
    }

    fun save(market: MarketEntity) {
        OrderBookSnapshotTable.upsert(
            OrderBookSnapshotTable.marketGuid,
        ) {
            it[this.marketGuid] = market.guid.value
            it[this.orderBook] = this@OrderBookSnapshot
            it[this.updatedAt] = Clock.System.now()
        }
    }

    fun diff(prevSnapshot: OrderBookSnapshot): Diff {
        fun entriesDiff(prevEntries: List<Entry>, currentEntries: List<Entry>): List<Entry> {
            val prevByPrice = prevEntries.associateBy { it.price }
            val newByPrice = currentEntries.associateBy { it.price }

            val updatedEntries = prevEntries.mapNotNull { prevEntry ->
                val newEntry = newByPrice[prevEntry.price] ?: Entry(prevEntry.price, BigDecimal.ZERO.setScale(prevEntry.price.scale()))
                if (newEntry != prevEntry) {
                    newEntry
                } else {
                    null
                }
            }

            val addedEntries = currentEntries.filter { newEntry -> !prevByPrice.containsKey(newEntry.price) }
            return (updatedEntries + addedEntries).sortedByDescending { it.price }
        }

        return Diff(
            bids = entriesDiff(prevSnapshot.bids, bids),
            asks = entriesDiff(prevSnapshot.asks, asks),
            last = if (prevSnapshot.last == last) {
                null
            } else {
                last
            },
        )
    }
}

object OrderBookSnapshotTable : Table("order_book_snapshot") {
    val marketGuid = reference("market_guid", MarketTable)
    val orderBook = jsonb<OrderBookSnapshot>("order_book", KotlinxSerialization.json)
    val updatedAt = timestamp("updated_at").nullable()

    override val primaryKey = PrimaryKey(marketGuid)
}
