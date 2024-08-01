package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.WithdrawalId

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
