package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.WalletId

@Suppress("ClassName")
class V75_AddAutoReducedToOrder : Migration() {
    object V75_MarketTable : GUIDTable<MarketId>("market", ::MarketId)
    object V75_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId)

    @Serializable
    enum class V75_OrderType {
        Market,
        Limit,
        BackToBackMarket,
    }

    @Serializable
    enum class V75_OrderSide {
        Buy,
        Sell,
    }

    @Serializable
    enum class V75_OrderStatus {
        Open,
        Partial,
        Filled,
        Cancelled,
        Expired,
        Rejected,
        Failed,
    }

    object V75_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val nonce = varchar("nonce", 10485760).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val marketGuid = reference("market_guid", V75_MarketTable).index()
        val walletGuid = reference("wallet_guid", V75_WalletTable).index()

        val status = customEnumeration(
            "status",
            "OrderStatus",
            { value -> V75_OrderStatus.valueOf(value as String) },
            { PGEnum("OrderStatus", it) },
        ).index()
        val type = customEnumeration(
            "type",
            "OrderType",
            { value -> V75_OrderType.valueOf(value as String) },
            { PGEnum("OrderType", it) },
        )
        val side = customEnumeration(
            "side",
            "OrderSide",
            { value -> V75_OrderSide.valueOf(value as String) },
            { PGEnum("OrderSide", it) },
        )
        val amount = decimal("amount", 30, 0)
        val originalAmount = decimal("original_amount", 30, 0)
        val autoReduced = bool("auto_reduced").default(false)
        val price = decimal("price", 30, 18).nullable()
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val closedAt = timestamp("closed_at").nullable()
        val closedBy = varchar("closed_by", 10485760).nullable()
        val signature = varchar("signature", 10485760)
        val sequencerOrderId = long("sequencer_order_id").uniqueIndex().nullable()
        val clientOrderId = varchar("client_order_id", 10485760).uniqueIndex().nullable()
        val sequencerTimeNs = decimal("sequencer_time_ns", 30, 0)
        val secondMarketGuid = reference("second_market_guid", V75_MarketTable).nullable()

        init {
            V75_OrderTable.index(
                customIndexName = "order_wallet_guid_created_at_index",
                columns = arrayOf(walletGuid, createdAt),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V75_OrderTable)
        }
    }
}
