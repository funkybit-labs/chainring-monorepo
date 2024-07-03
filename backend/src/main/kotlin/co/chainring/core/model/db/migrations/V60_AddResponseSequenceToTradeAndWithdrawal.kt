package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.WithdrawalId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

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
