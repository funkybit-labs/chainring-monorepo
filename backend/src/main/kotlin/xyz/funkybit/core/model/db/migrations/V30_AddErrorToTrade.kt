package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.TradeId

@Suppress("ClassName")
class V30_AddErrorToTrade : Migration() {

    object V30_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val error = varchar("error", 10485760).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V30_TradeTable)
        }
    }
}
