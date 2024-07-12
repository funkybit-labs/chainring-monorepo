package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.ExecutionId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.PGEnum
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

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
