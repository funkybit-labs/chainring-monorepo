package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V66_AddCounterOrderIdToOrderExecution : Migration() {
    override fun run() {
        transaction {
            exec("ALTER TABLE order_execution ADD COLUMN counter_order_guid CHARACTER VARYING(10485760)")
            exec("UPDATE order_execution SET counter_order_guid = (SELECT oe.guid FROM order_execution oe WHERE oe.trade_guid = trade_guid AND oe.role != role)")
            exec("ALTER TABLE order_execution ALTER COLUMN counter_order_guid SET NOT NULL")
        }
    }
}
