package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.OrderId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V69_ClientOrderId : Migration() {

    object V69_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val clientOrderId = varchar("client_order_id", 10485760).uniqueIndex().nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V69_OrderTable)
        }
    }
}
