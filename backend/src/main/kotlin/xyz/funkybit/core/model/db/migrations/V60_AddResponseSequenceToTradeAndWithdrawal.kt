package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.TradeId
import xyz.funkybit.core.model.db.WithdrawalId

@Suppress("ClassName")
class V60_AddResponseSequenceToTradeAndWithdrawal : Migration() {

    object V60_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val responseSequence = long("response_sequence").nullable().index()
    }

    object V60_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val responseSequence = long("response_sequence").nullable().index()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V60_TradeTable, V60_WithdrawalTable)
            exec("CREATE INDEX trade_response_sequence_pending_settlement_status ON trade (response_sequence, settlement_status) WHERE (settlement_status = 'Pending'::settlementstatus)")
        }
    }
}
