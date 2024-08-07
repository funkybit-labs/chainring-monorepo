package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.BalanceLogId
import co.chainring.core.model.db.GUIDTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V71_BalanceLogTableChanges : Migration() {
    object V71_BalanceLogTable : GUIDTable<BalanceLogId>("balance_log", ::BalanceLogId) {
        val amount = (decimal("amount", 30, 0)).nullable()
        val isReplacement = bool("is_replacement").nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V71_BalanceLogTable)
            exec(
                """
                UPDATE balance_log SET amount = delta, is_replacement = 'f';
                ALTER TABLE balance_log DROP COLUMN delta;
                ALTER TABLE balance_log DROP COLUMN balance_before;
                ALTER TABLE balance_log DROP COLUMN balance_after;
                ALTER TABLE balance_log ALTER COLUMN amount SET NOT NULL;
                ALTER TABLE balance_log ALTER COLUMN is_replacement SET NOT NULL;
                """.trimIndent(),
            )
        }
    }
}
