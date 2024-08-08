package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.BlockchainTransactionId
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V29_ExchangeTransactionBatch : Migration() {

    @JvmInline
    value class V29_ExchangeTransactionId(override val value: String) : EntityId

    @JvmInline
    value class V29_ExchangeTransactionBatchId(override val value: String) : EntityId

    object V29_BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>(
        "blockchain_transaction",
        ::BlockchainTransactionId,
    )

    private object V29_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    enum class V29_ExchangeTransactionBatchStatus {
        Preparing,
        Prepared,
        Submitted,
        Completed,
        Failed,
    }

    object V29_ExchangeTransactionBatchTable : GUIDTable<V29_ExchangeTransactionBatchId>(
        "exchange_transaction_batch",
        ::V29_ExchangeTransactionBatchId,
    ) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val status = customEnumeration(
            "status",
            "ExchangeTransactionBatchStatus",
            { value -> V29_ExchangeTransactionBatchStatus.valueOf(value as String) },
            { PGEnum("ExchangeTransactionBatchStatus", it) },
        ).index()
        val error = varchar("error", 10485760).nullable()
        val sequenceId = integer("sequence_id").autoIncrement()
        val chainId = reference("chain_id", V29_ChainTable)
        val prepareBlockchainTransactionGuid = reference(
            "prepare_tx_guid",
            V29_BlockchainTransactionTable,
        )
        val submitBlockchainTransactionGuid = reference(
            "submit_tx_guid",
            V29_BlockchainTransactionTable,
        ).nullable()
    }

    enum class V29_ExchangeTransactionStatus {
        Pending,
        Assigned,
        Failed,
        Completed,
    }

    object V29_ExchangeTransactionTable : GUIDTable<V29_ExchangeTransactionId>(
        "exchange_transaction",
        ::V29_ExchangeTransactionId,
    ) {
        val status = customEnumeration(
            "status",
            "ExchangeTransactionStatus",
            { value -> V29_ExchangeTransactionStatus.valueOf(value as String) },
            { PGEnum("ExchangeTransactionStatus", it) },
        )
        val exchangeTransactionBatchGuid = reference(
            "exchange_transaction_batch_guid",
            V29_ExchangeTransactionBatchTable,
        ).index().nullable()
        val error = varchar("error", 10485760).nullable()
    }

    override fun run() {
        transaction {
            exec("DROP INDEX exchange_transaction_pending_status")
            updateEnum<V29_ExchangeTransactionStatus>(listOf(V29_ExchangeTransactionTable.status), "ExchangeTransactionStatus")
            exec("CREATE INDEX exchange_transaction_pending_status ON exchange_transaction (status) WHERE status = 'Pending'::exchangetransactionstatus")
            exec("ALTER TABLE exchange_transaction DROP COLUMN blockchain_transaction_guid")
            exec("CREATE TYPE ExchangeTransactionBatchStatus AS ENUM (${enumDeclaration<V29_ExchangeTransactionBatchStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V29_ExchangeTransactionBatchTable, V29_ExchangeTransactionTable)
        }
    }
}
