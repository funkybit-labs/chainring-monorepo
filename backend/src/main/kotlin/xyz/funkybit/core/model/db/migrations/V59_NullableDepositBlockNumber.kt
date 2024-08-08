package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V59_NullableDepositBlockNumber : Migration() {
    override fun run() {
        transaction {
            exec("ALTER TABLE deposit ALTER COLUMN block_number DROP NOT NULL")
            exec("UPDATE deposit SET block_number = NULL WHERE block_number = 0")
        }
    }
}
