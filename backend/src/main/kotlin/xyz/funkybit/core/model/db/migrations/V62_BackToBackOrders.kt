package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.ExecutionId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.PGEnum

@Suppress("ClassName")
class V62_BackToBackOrders : Migration() {

    enum class V62_OrderType {
        Market,
        Limit,
        BackToBackMarket,
    }

    object V62MarketTable : GUIDTable<MarketId>("market", ::MarketId)

    object V62_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val type = customEnumeration(
            "type",
            "OrderType",
            { value -> V62_OrderType.valueOf(value as String) },
            { PGEnum("OrderType", it) },
        )
        val secondMarketGuid = reference("second_market_guid", V62MarketTable).nullable()
    }

    object V62_OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
        val marketGuid = reference("market_guid", V62MarketTable).nullable()
    }

    override fun run() {
        transaction {
            updateEnum<V62_OrderType>(listOf(V62_OrderTable.type), "OrderType")
            SchemaUtils.createMissingTablesAndColumns(V62_OrderTable, V62_OrderExecutionTable)
        }
    }
}
