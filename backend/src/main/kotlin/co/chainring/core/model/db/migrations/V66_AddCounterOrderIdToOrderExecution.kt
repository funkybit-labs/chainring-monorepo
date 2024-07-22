package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ExecutionId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.OrderId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V66_AddCounterOrderIdToOrderExecution : Migration() {

    object V66_OrderTable : GUIDTable<OrderId>("order", ::OrderId)
    object V66_OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
        val counterOrderGuid = reference(
            "counter_order_guid",
            V66_OrderTable,
        ).index().nullable()
    }
    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V66_OrderExecutionTable)
            exec("UPDATE order_execution oe SET counter_order_guid = (SELECT order_guid FROM order_execution WHERE trade_guid = oe.trade_guid AND role != oe.role)")
            exec("ALTER TABLE order_execution ALTER COLUMN counter_order_guid SET NOT NULL")
        }
    }
}
