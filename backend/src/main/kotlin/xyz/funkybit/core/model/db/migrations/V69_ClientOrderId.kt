package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.OrderId

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
