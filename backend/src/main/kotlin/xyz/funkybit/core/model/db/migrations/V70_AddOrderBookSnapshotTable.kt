package xyz.funkybit.core.model.db.migrations

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.ExecutionId
import xyz.funkybit.core.model.db.GUIDEntity
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.TradeId
import xyz.funkybit.core.utils.fromFundamentalUnits
import java.math.BigDecimal
import java.math.RoundingMode

@Suppress("ClassName")
class V70_AddOrderBookSnapshotTable : Migration() {
    object V70_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    object V70_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val name = varchar("name", 10485760)
        val chainId = reference("chain_id", V70_ChainTable)
        val decimals = ubyte("decimals")
    }

    class V70_SymbolEntity(guid: EntityID<SymbolId>) : GUIDEntity<SymbolId>(guid) {
        companion object : EntityClass<SymbolId, V70_SymbolEntity>(V70_SymbolTable)
        var name by V70_SymbolTable.name
        var chainId by V70_SymbolTable.chainId
        var decimals by V70_SymbolTable.decimals
    }

    object V70_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val baseSymbolGuid = reference("base_symbol_guid", V70_SymbolTable)
        val quoteSymbolGuid = reference("quote_symbol_guid", V70_SymbolTable)
        val tickSize = decimal("tick_size", 30, 18)
        val lastPrice = decimal("last_price", 30, 18)
    }

    class V70_MarketEntity(guid: EntityID<MarketId>) : GUIDEntity<MarketId>(guid) {
        companion object : EntityClass<MarketId, V70_MarketEntity>(V70_MarketTable) {
            override fun all(): SizedIterable<V70_MarketEntity> =
                table
                    .selectAll()
                    .orderBy(table.id, SortOrder.ASC)
                    .notForUpdate()
                    .let { wrapRows(it) }
                    .with(V70_MarketEntity::baseSymbol, V70_MarketEntity::quoteSymbol)
        }

        var baseSymbolGuid by V70_MarketTable.baseSymbolGuid
        var quoteSymbolGuid by V70_MarketTable.quoteSymbolGuid
        var tickSize by V70_MarketTable.tickSize
        var lastPrice by V70_MarketTable.lastPrice

        var baseSymbol by V70_SymbolEntity referencedOn V70_MarketTable.baseSymbolGuid
        var quoteSymbol by V70_SymbolEntity referencedOn V70_MarketTable.quoteSymbolGuid

        fun priceScale(): Int =
            tickSize.stripTrailingZeros().scale() + 1
    }

    @Serializable
    enum class V70_OrderType {
        Market,
        Limit,
        BackToBackMarket,
    }

    @Serializable
    enum class V70_OrderSide {
        Buy,
        Sell,
    }

    @Serializable
    enum class V70_OrderStatus {
        Open,
        Partial,
        Filled,
        Cancelled,
        Expired,
        Rejected,
        Failed,
    }

    object V70_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val nonce = varchar("nonce", 10485760).index()
        val marketGuid = reference("market_guid", V70_MarketTable).index()

        val status = customEnumeration(
            "status",
            "OrderStatus",
            { value -> V70_OrderStatus.valueOf(value as String) },
            { PGEnum("OrderStatus", it) },
        ).index()
        val type = customEnumeration(
            "type",
            "OrderType",
            { value -> V70_OrderType.valueOf(value as String) },
            { PGEnum("OrderType", it) },
        )
        val side = customEnumeration(
            "side",
            "OrderSide",
            { value -> V70_OrderSide.valueOf(value as String) },
            { PGEnum("OrderSide", it) },
        )
        val amount = decimal("amount", 30, 0)
        val price = decimal("price", 30, 18).nullable()
    }

    object V70_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val marketGuid = reference("market_guid", V70_MarketTable).index()
        val timestamp = timestamp("timestamp")
        val amount = decimal("amount", 30, 0)
        val price = decimal("price", 30, 18)
    }

    object V70_OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
        val timestamp = timestamp("timestamp").index()
        val orderGuid = reference("order_guid", V70_OrderTable).index()
        val tradeGuid = reference("trade_guid", V70_TradeTable).index()
        val marketGuid = reference("market_guid", V70_MarketTable).nullable()
    }

    @Serializable
    data class V70_OrderBookSnapshot(
        val bids: List<Entry>,
        val asks: List<Entry>,
        val last: LastTrade,
    ) {
        @Serializable
        data class Entry(
            val price: String,
            val size: BigDecimalJson,
        )

        @Serializable
        data class LastTrade(
            val price: String,
            val direction: LastTradeDirection,
        )

        @Serializable
        enum class LastTradeDirection {
            Up,
            Down,
            Unchanged,
        }

        companion object {
            fun calculate(market: V70_MarketEntity): V70_OrderBookSnapshot {
                val priceScale = market.priceScale()

                fun getOrderBookEntries(side: V70_OrderSide): List<Entry> {
                    val sizeCol = V70_OrderTable.amount.sum().alias("size")

                    return V70_OrderTable
                        .select(V70_OrderTable.price, sizeCol)
                        .where { V70_OrderTable.marketGuid.eq(market.guid) }
                        .andWhere { V70_OrderTable.type.eq(V70_OrderType.Limit) }
                        .andWhere { V70_OrderTable.side.eq(side) }
                        .andWhere { V70_OrderTable.status.inList(listOf(V70_OrderStatus.Open, V70_OrderStatus.Partial)) }
                        .andWhere { V70_OrderTable.price.isNotNull() }
                        .groupBy(V70_OrderTable.price)
                        .orderBy(V70_OrderTable.price, SortOrder.DESC)
                        .toList()
                        .mapNotNull {
                            val price = it[V70_OrderTable.price] ?: return@mapNotNull null
                            val size = it[sizeCol] ?: return@mapNotNull null
                            Entry(
                                price = price.setScale(priceScale).toString(),
                                size = size.toBigInteger().fromFundamentalUnits(market.baseSymbol.decimals).stripTrailingZeros(),
                            )
                        }
                }

                // We calculate last trade's price as a size-weighted average
                // of all execution prices from the last match
                val weightedAveragePriceCol = V70_TradeTable.price.times(V70_TradeTable.amount).sum().div(V70_TradeTable.amount.sum())

                val (lastTradePrice, prevTradePrice) = V70_OrderExecutionTable
                    .join(V70_OrderTable, JoinType.LEFT, V70_OrderExecutionTable.orderGuid, V70_OrderTable.guid)
                    .leftJoin(V70_TradeTable)
                    .select(weightedAveragePriceCol)
                    .where { V70_OrderTable.marketGuid.eq(market.guid) }
                    .andWhere { V70_OrderTable.type.eq(V70_OrderType.Market) }
                    .groupBy(
                        V70_OrderExecutionTable.orderGuid,
                        V70_OrderExecutionTable.timestamp,
                    )
                    .orderBy(V70_OrderExecutionTable.timestamp, SortOrder.DESC)
                    .limit(2)
                    .mapNotNull { it[weightedAveragePriceCol] }
                    .let {
                        Pair(
                            it.getOrElse(0) { BigDecimal.ZERO },
                            it.getOrElse(1) { BigDecimal.ZERO },
                        )
                    }

                return V70_OrderBookSnapshot(
                    bids = getOrderBookEntries(V70_OrderSide.Buy),
                    asks = getOrderBookEntries(V70_OrderSide.Sell),
                    last = LastTrade(
                        price = lastTradePrice.setScale(priceScale, RoundingMode.HALF_EVEN).toString(),
                        direction = when {
                            lastTradePrice > prevTradePrice -> LastTradeDirection.Up
                            lastTradePrice < prevTradePrice -> LastTradeDirection.Down
                            else -> LastTradeDirection.Unchanged
                        },
                    ),
                )
            }
        }

        fun save(market: V70_MarketEntity) {
            V70_OrderBookSnapshotTable.insert {
                it[this.marketGuid] = market.guid.value
                it[this.orderBook] = this@V70_OrderBookSnapshot
                it[this.updatedAt] = Clock.System.now()
            }
        }
    }

    object V70_OrderBookSnapshotTable : Table("order_book_snapshot") {
        val marketGuid = reference("market_guid", V70_MarketTable)
        val orderBook = jsonb<V70_OrderBookSnapshot>("order_book", KotlinxSerialization.json)
        val updatedAt = timestamp("updated_at").nullable()

        override val primaryKey = PrimaryKey(marketGuid)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V70_OrderBookSnapshotTable)
            V70_MarketEntity.all().forEach { market ->
                V70_OrderBookSnapshot.calculate(market).save(market)
            }
        }
    }
}
