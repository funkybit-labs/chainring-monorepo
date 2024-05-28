package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@JvmInline
value class ExchangeTransactionBatchId(override val value: String) : EntityId {
    companion object {
        fun generate(): ExchangeTransactionBatchId = ExchangeTransactionBatchId(TypeId.generate("etbatch").toString())
    }

    override fun toString(): String = value
}

enum class ExchangeTransactionBatchStatus {
    Preparing,
    Prepared,
    Submitted,
    Completed,
    Failed,
}

object ExchangeTransactionBatchTable : GUIDTable<ExchangeTransactionBatchId>("exchange_transaction_batch", ::ExchangeTransactionBatchId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val status = customEnumeration(
        "status",
        "ExchangeTransactionBatchStatus",
        { value -> ExchangeTransactionBatchStatus.valueOf(value as String) },
        { PGEnum("ExchangeTransactionBatchStatus", it) },
    ).index()
    val error = varchar("error", 10485760).nullable()
    val sequenceId = integer("sequence_id").autoIncrement()
    val chainId = reference("chain_id", ChainTable)
    val prepareBlockchainTransactionGuid = reference(
        "prepare_tx_guid",
        BlockchainTransactionTable,
    ).index()
    val submitBlockchainTransactionGuid = reference(
        "submit_tx_guid",
        BlockchainTransactionTable,
    ).index().nullable()

    init {
        index(
            customIndexName = "exchange_transaction_batch_sequencer_chain_status",
            columns = arrayOf(sequenceId, chainId, status),
            filterCondition = {
                status.neq(ExchangeTransactionBatchStatus.Completed)
            },
        )
    }
}

class ExchangeTransactionBatchEntity(guid: EntityID<ExchangeTransactionBatchId>) : GUIDEntity<ExchangeTransactionBatchId>(guid) {
    companion object : EntityClass<ExchangeTransactionBatchId, ExchangeTransactionBatchEntity>(ExchangeTransactionBatchTable) {
        fun create(
            chainId: ChainId,
            transactions: List<ExchangeTransactionEntity>,
            prepareBlockchainTransaction: BlockchainTransactionEntity,
        ): ExchangeTransactionBatchEntity {
            val entity = ExchangeTransactionBatchEntity.new(ExchangeTransactionBatchId.generate()) {
                val now = Clock.System.now()
                this.createdAt = now
                this.createdBy = "system"
                this.status = ExchangeTransactionBatchStatus.Preparing
                this.chainId = EntityID(chainId, ChainTable)
                this.prepareBlockchainTransactionGuid = prepareBlockchainTransaction.guid
            }
            entity.refresh(flush = true)
            ExchangeTransactionEntity.assignToBatch(transactions.map { it.guid.value }, entity)
            return entity
        }

        fun findCurrentBatch(chainId: ChainId): ExchangeTransactionBatchEntity? {
            return ExchangeTransactionBatchEntity
                .find {
                    ExchangeTransactionBatchTable.chainId.eq(chainId) and
                        ExchangeTransactionBatchTable.status.neq(ExchangeTransactionBatchStatus.Completed)
                }
                .orderBy(ExchangeTransactionBatchTable.sequenceId to SortOrder.ASC)
                .firstOrNull()
        }
    }

    fun markAsSubmitted() {
        this.status = ExchangeTransactionBatchStatus.Submitted
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsPrepared() {
        this.status = ExchangeTransactionBatchStatus.Prepared
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsCompleted() {
        this.status = ExchangeTransactionBatchStatus.Completed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
        ExchangeTransactionEntity.markAsCompleted(this)
    }

    fun markAsFailed(error: String) {
        this.status = ExchangeTransactionBatchStatus.Failed
        this.error = error
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    var createdAt by ExchangeTransactionBatchTable.createdAt
    var createdBy by ExchangeTransactionBatchTable.createdBy
    var updatedAt by ExchangeTransactionBatchTable.updatedAt
    var updatedBy by ExchangeTransactionBatchTable.updatedBy
    var status by ExchangeTransactionBatchTable.status
    var error by ExchangeTransactionBatchTable.error
    var sequenceId by ExchangeTransactionBatchTable.sequenceId

    var chainId by ExchangeTransactionBatchTable.chainId
    var chain by ChainEntity referencedOn ExchangeTransactionBatchTable.chainId

    var prepareBlockchainTransactionGuid by ExchangeTransactionBatchTable.prepareBlockchainTransactionGuid
    var prepareBlockchainTransaction by BlockchainTransactionEntity referencedOn ExchangeTransactionBatchTable.prepareBlockchainTransactionGuid

    var submitBlockchainTransactionGuid by ExchangeTransactionBatchTable.submitBlockchainTransactionGuid
    var submitBlockchainTransaction by BlockchainTransactionEntity optionalReferencedOn ExchangeTransactionBatchTable.submitBlockchainTransactionGuid
}
