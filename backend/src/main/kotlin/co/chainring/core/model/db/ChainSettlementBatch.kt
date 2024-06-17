package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll

@JvmInline
value class ChainSettlementBatchId(override val value: String) : EntityId {
    companion object {
        fun generate(): ChainSettlementBatchId = ChainSettlementBatchId(TypeId.generate("csbatch").toString())
    }

    override fun toString(): String = value
}

object ChainSettlementBatchTable : GUIDTable<ChainSettlementBatchId>("chain_settlement_batch", ::ChainSettlementBatchId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val status = customEnumeration(
        "status",
        "SettlementBatchStatus",
        { value -> SettlementBatchStatus.valueOf(value as String) },
        { PGEnum("SettlementBatchStatus", it) },
    ).index()
    val chainId = reference("chain_id", ChainTable).index()
    val settlementBatchGuid = reference(
        "settlement_batch_guid",
        SettlementBatchTable,
    ).index()
    val preparationTxGuid = reference(
        "preparation_tx_guid",
        BlockchainTransactionTable,
    )
    val submissionTxGuid = reference(
        "submission_tx_guid",
        BlockchainTransactionTable,
    ).nullable()
    val rollbackTxGuid = reference(
        "rollback_tx_guid",
        BlockchainTransactionTable,
    ).nullable()
    val error = varchar("error", 10485760).nullable()
}

class ChainSettlementBatchEntity(guid: EntityID<ChainSettlementBatchId>) : GUIDEntity<ChainSettlementBatchId>(guid) {
    companion object : EntityClass<ChainSettlementBatchId, ChainSettlementBatchEntity>(ChainSettlementBatchTable) {
        fun create(
            chainId: ChainId,
            settlementBatch: SettlementBatchEntity,
            preparationTx: BlockchainTransactionEntity,
        ): ChainSettlementBatchEntity {
            val entity = ChainSettlementBatchEntity.new(ChainSettlementBatchId.generate()) {
                val now = Clock.System.now()
                this.createdAt = now
                this.createdBy = "system"
                this.status = SettlementBatchStatus.Preparing
                this.chainId = EntityID(chainId, ChainTable)
                this.settlementBatchGuid = settlementBatch.guid
                this.preparationTxGuid = preparationTx.guid
            }
            entity.refresh(flush = true)
            return entity
        }

        fun findInProgressBatch(chainId: ChainId): ChainSettlementBatchEntity? {
            return ChainSettlementBatchTable
                .join(SettlementBatchTable, JoinType.INNER, SettlementBatchTable.guid, ChainSettlementBatchTable.settlementBatchGuid)
                .selectAll().where {
                    SettlementBatchTable.status.neq(SettlementBatchStatus.Completed) and
                        ChainSettlementBatchTable.chainId.eq(chainId)
                }
                .orderBy(SettlementBatchTable.sequenceId to SortOrder.ASC)
                .map { ChainSettlementBatchEntity.wrapRow(it) }
                .firstOrNull()
        }
    }

    fun isPreparedOrCompleted() = listOf(SettlementBatchStatus.Prepared, SettlementBatchStatus.Completed).contains(this.status)

    fun isRolledBackOrCompleted() = listOf(SettlementBatchStatus.RolledBack, SettlementBatchStatus.Completed).contains(this.status)

    fun isSubmittedOrCompleted() = listOf(SettlementBatchStatus.Submitted, SettlementBatchStatus.Completed).contains(this.status)

    fun isCompleted() = SettlementBatchStatus.Completed == this.status

    fun markAsPreparing(blockchainTransactionEntity: BlockchainTransactionEntity) {
        this.status = SettlementBatchStatus.Preparing
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
        this.preparationTxGuid = blockchainTransactionEntity.guid
    }

    fun markAsPrepared() {
        this.status = SettlementBatchStatus.Prepared
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsSubmitting(blockchainTransactionEntity: BlockchainTransactionEntity) {
        this.status = SettlementBatchStatus.Submitting
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
        this.submissionTxGuid = blockchainTransactionEntity.guid
    }

    fun markAsSubmitted() {
        this.status = SettlementBatchStatus.Submitted
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsRolledBack() {
        this.status = SettlementBatchStatus.RolledBack
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsCompleted() {
        this.status = SettlementBatchStatus.Completed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsRollingBack(blockchainTransactionEntity: BlockchainTransactionEntity) {
        this.status = SettlementBatchStatus.RollingBack
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
        this.rollbackTxGuid = blockchainTransactionEntity.guid
    }

    fun markAsFailed(error: String) {
        this.status = SettlementBatchStatus.Failed
        this.error = error
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    var createdAt by ChainSettlementBatchTable.createdAt
    var createdBy by ChainSettlementBatchTable.createdBy
    var updatedAt by ChainSettlementBatchTable.updatedAt
    var updatedBy by ChainSettlementBatchTable.updatedBy
    var status by ChainSettlementBatchTable.status
    var error by ChainSettlementBatchTable.error

    var chainId by ChainSettlementBatchTable.chainId
    var chain by ChainEntity referencedOn ChainSettlementBatchTable.chainId

    var settlementBatchGuid by ChainSettlementBatchTable.settlementBatchGuid
    var settlementBatch by SettlementBatchEntity referencedOn ChainSettlementBatchTable.settlementBatchGuid

    var preparationTxGuid by ChainSettlementBatchTable.preparationTxGuid
    var prepararationTx by BlockchainTransactionEntity referencedOn ChainSettlementBatchTable.preparationTxGuid

    var submissionTxGuid by ChainSettlementBatchTable.submissionTxGuid
    var submissionTx by BlockchainTransactionEntity optionalReferencedOn ChainSettlementBatchTable.submissionTxGuid

    var rollbackTxGuid by ChainSettlementBatchTable.rollbackTxGuid
    var rollbackTx by BlockchainTransactionEntity optionalReferencedOn ChainSettlementBatchTable.rollbackTxGuid
}
