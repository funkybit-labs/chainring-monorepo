package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.db.updateEnum
import co.chainring.core.model.db.BlockchainTransactionId
import co.chainring.core.model.db.BlockchainTransactionTable
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainIdColumnType
import co.chainring.core.model.db.ChainSettlementBatchId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.SettlementBatchId
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.enumDeclaration
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V33_SettlementBatch : Migration() {

    object V33_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>(
        "blockchain_transaction",
        ::BlockchainTransactionId,
    )

    private object V33_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    enum class V33_SettlementBatchStatus {
        Preparing,
        Prepared,
        RollingBack,
        RolledBack,
        Submitting,
        Submitted,
        Completed,
        Failed,
    }

    object V33_SettlementBatchTable : GUIDTable<SettlementBatchId>(
        "settlement_batch",
        ::SettlementBatchId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val status = customEnumeration(
            "status",
            "SettlementBatchStatus",
            { value -> V33_SettlementBatchStatus.valueOf(value as String) },
            { PGEnum("SettlementBatchStatus", it) },
        ).index()
        val sequenceId = integer("sequence_id").autoIncrement()
    }

    object V33_ChainSettlementBatchTable : GUIDTable<ChainSettlementBatchId>(
        "chain_settlement_batch",
        ::ChainSettlementBatchId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val status = customEnumeration(
            "status",
            "SettlementBatchStatus",
            { value -> V33_SettlementBatchStatus.valueOf(value as String) },
            { PGEnum("SettlementBatchStatus", it) },
        ).index()
        val chainId = reference("chain_id", V33_ChainTable)
        val settlementBatchGuid = reference(
            "settlement_batch_guid",
            V33_SettlementBatchTable,
        )
        val preparationTxGuid = reference(
            "preparation_tx_guid",
            V33_BlockchainTransactionTable,
        )
        val submissionTxGuid = reference(
            "submission_tx_guid",
            V33_BlockchainTransactionTable,
        ).nullable()
        val rollbackTxGuid = reference(
            "rollback_tx_guid",
            BlockchainTransactionTable,
        ).nullable()
        val error = varchar("error", 10485760).nullable()
    }

    enum class V33_SettlementStatus {
        Pending,
        Settling,
        FailedSettling,
        Completed,
        Failed,
    }
    object V33_TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
        val settlementStatus = customEnumeration(
            "settlement_status",
            "SettlementStatus",
            { value -> V33_SettlementStatus.valueOf(value as String) },
            { PGEnum("SettlementStatus", it) },
        )
        val sequenceId = integer("sequence_id").autoIncrement().index()
        val tradeHash = varchar("trade_hash", 10485760).uniqueIndex().nullable()
        val settlementBatchGuid = reference("settlement_batch_guid", V33_SettlementBatchTable).index().nullable()
    }

    override fun run() {
        transaction {
            updateEnum<V33_SettlementStatus>(listOf(V33_TradeTable.settlementStatus), "SettlementStatus")
            exec("CREATE TYPE SettlementBatchStatus AS ENUM (${enumDeclaration<V33_SettlementBatchStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V33_SettlementBatchTable, V33_ChainSettlementBatchTable, V33_TradeTable)

            exec("""update trade set trade_hash = (SELECT md5(random()::text))""")
            exec("""ALTER TABLE trade ALTER COLUMN trade_hash SET NOT NULL""")

            exec("DROP TABLE exchange_transaction")
            exec("DROP TABLE exchange_transaction_batch")
            exec("DROP TYPE exchangetransactionstatus")
            exec("DROP TYPE exchangetransactionbatchstatus")

            exec("CREATE INDEX trade_sequence_pending_settlement_status ON trade (sequence_id, settlement_status) WHERE (settlement_status = 'Pending'::settlementstatus)")
        }
    }
}
