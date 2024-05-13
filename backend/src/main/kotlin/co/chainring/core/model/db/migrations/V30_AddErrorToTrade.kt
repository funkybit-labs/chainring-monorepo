package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.TradeId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

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
