package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.WithdrawalId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V41_AddActualAmountToWithdrawalTable : Migration() {

    object V41_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val actualAmount = decimal("actual_amount", 30, 0).nullable()
    }

    override fun run() {
        transaction {
            exec("alter table withdrawal alter column sequence_id type bigint")
            SchemaUtils.createMissingTablesAndColumns(V41_WithdrawalTable)
        }
    }
}
