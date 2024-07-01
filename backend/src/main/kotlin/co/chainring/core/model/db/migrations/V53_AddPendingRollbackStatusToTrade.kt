package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.TradeId
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V53_AddPendingRollbackStatusToTrade : Migration() {

    enum class V53_SettlementStatus {
        Pending,
        Settling,
        PendingRollback,
        FailedSettling,
        Completed,
        Failed,
    }
    object V53_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val settlementStatus = customEnumeration(
            "settlement_status",
            "SettlementStatus",
            { value -> V53_SettlementStatus.valueOf(value as String) },
            { PGEnum("SettlementStatus", it) },
        )
    }

    override fun run() {
        transaction {
            exec("DROP INDEX trade_sequence_pending_settlement_status")
            updateEnum<V53_SettlementStatus>(listOf(V53_TradeTable.settlementStatus), "SettlementStatus")
            exec("CREATE INDEX trade_sequence_pending_settlement_status ON trade (sequence_id, settlement_status) WHERE (settlement_status in ('Pending'::settlementstatus,'PendingRollback'::settlementstatus))")
        }
    }
}
