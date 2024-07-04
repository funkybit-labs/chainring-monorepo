package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V59_NullableDepositBlockNumber : Migration() {
    override fun run() {
        transaction {
            exec("ALTER TABLE deposit ALTER COLUMN block_number DROP NOT NULL")
            exec("UPDATE deposit SET block_number = NULL WHERE block_number = 0")
        }
    }
}
