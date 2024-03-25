package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ExecutionId
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketTable
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.enumDeclaration
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V8_ExecutionsAndTrades : Migration() {

    object V8_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val ownerAddress = varchar("owner_address", 10485760).nullable().index()
    }

    @Serializable
    enum class V8_SettlementStatus {
        Pending,
        Completed,
        Failed,
    }

    object V8_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val createdAt = timestamp("created_at")
        val marketGuid = reference("market_guid", MarketTable).index()
        val timestamp = timestamp("timestamp")
        val amount = decimal("amount", 30, 0)
        val price = decimal("price", 30, 0)
        val settlementStatus = customEnumeration(
            "settlement_status",
            "SettlementStatus",
            { value -> V8_SettlementStatus.valueOf(value as String) },
            { PGEnum("SettlementStatus", it) },
        )
        val settledAt = timestamp("settled_at").nullable()
    }

    @Serializable
    enum class V8_ExecutionRole {
        Taker,
        Maker,
    }

    object V8_OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
        val createdAt = timestamp("created_at")
        val timestamp = timestamp("timestamp")
        val orderGuid = reference("order_guid", OrderTable).index()
        val tradeGuid = reference("trade_guid", TradeTable).index()
        val role = customEnumeration(
            "role",
            "ExecutionRole",
            { value -> ExecutionRole.valueOf(value as String) },
            { PGEnum("ExecutionRole", it) },
        ).index()
        val feeAmount = decimal("fee_amount", 30, 0)
        val feeSymbol = varchar("fee_symbol", 10485760)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V8_OrderTable)
            exec("""UPDATE "order" SET owner_address = '0xb6De2e85F3d5E3B87780EF62a21bfEC01997b038' WHERE owner_address IS NULL""")
            exec("""ALTER TABLE "order" ALTER COLUMN owner_address SET NOT NULL""")

            exec("CREATE TYPE SettlementStatus AS ENUM (${enumDeclaration<V8_SettlementStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V8_TradeTable)

            exec("CREATE TYPE ExecutionRole AS ENUM (${enumDeclaration<V8_ExecutionRole>()})")
            SchemaUtils.createMissingTablesAndColumns(V8_OrderExecutionTable)
        }
    }
}
