package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
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
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.setScale
import xyz.funkybit.core.utils.sum
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

        fun calculate(market: MarketEntity, latestTradesWithTakerOrders: List<Pair<TradeEntity, OrderEntity>>, prevSnapshot: OrderBookSnapshot): OrderBookSnapshot {
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

            val latestTradesWithTakerOrdersForMarket = latestTradesWithTakerOrders
                .filter { it.first.marketGuid.value == market.guid.value }

            val latestOrderIds = latestTradesWithTakerOrdersForMarket
                .map { it.second.guid.value }
                .distinct()

            // We calculate last trade's price as a size-weighted average
            // of all execution prices from the last match
            fun weightedAvgPrice(trades: List<TradeEntity>): BigDecimal =
                trades
                    .map { it.price * it.amount.toBigDecimal() }
                    .sum()
                    .div(
                        trades.map { it.amount.toBigDecimal() }.sum(),
                    )

            val lastTrade = if (latestOrderIds.isNotEmpty()) {
                val latestOrderId = latestOrderIds.last()
                val lastTradePrice = weightedAvgPrice(
                    latestTradesWithTakerOrdersForMarket
                        .filter { it.second.guid.value == latestOrderId }
                        .map { it.first },
                ).setScale(priceScale, RoundingMode.HALF_EVEN)

                val prevTradePrice = if (latestOrderIds.size > 1) {
                    val orderId = latestOrderIds[latestOrderIds.size - 2]
                    weightedAvgPrice(
                        latestTradesWithTakerOrdersForMarket
                            .filter { it.second.guid.value == orderId }
                            .map { it.first },
                    )
                } else {
                    prevSnapshot.last.price
                }.setScale(priceScale, RoundingMode.HALF_EVEN)

                LastTrade(
                    lastTradePrice,
                    when {
                        lastTradePrice > prevTradePrice -> LastTradeDirection.Up
                        lastTradePrice < prevTradePrice -> LastTradeDirection.Down
                        else -> LastTradeDirection.Unchanged
                    },
                )
            } else {
                LastTrade(prevSnapshot.last.price, prevSnapshot.last.direction)
            }

            return OrderBookSnapshot(
                bids = getOrderBookEntries(OrderSide.Buy),
                asks = getOrderBookEntries(OrderSide.Sell),
                last = lastTrade,
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
