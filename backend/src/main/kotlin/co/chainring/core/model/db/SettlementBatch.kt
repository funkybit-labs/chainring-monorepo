package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@JvmInline
value class SettlementBatchId(override val value: String) : EntityId {
    companion object {
        fun generate(): SettlementBatchId = SettlementBatchId(TypeId.generate("stlbatch").toString())
    }

    override fun toString(): String = value
}

enum class SettlementBatchStatus {
    Preparing,
    Prepared,
    RollingBack,
    RolledBack,
    Submitting,
    Submitted,
    Completed,
    Failed,
}

object SettlementBatchTable : GUIDTable<SettlementBatchId>("settlement_batch", ::SettlementBatchId) {
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
    val sequenceId = integer("sequence_id").autoIncrement()
}

class SettlementBatchEntity(guid: EntityID<SettlementBatchId>) : GUIDEntity<SettlementBatchId>(guid) {
    companion object : EntityClass<SettlementBatchId, SettlementBatchEntity>(SettlementBatchTable) {
        fun create(): SettlementBatchEntity {
            val entity = SettlementBatchEntity.new(SettlementBatchId.generate()) {
                val now = Clock.System.now()
                this.createdAt = now
                this.createdBy = "system"
                this.status = SettlementBatchStatus.Preparing
            }
            return entity
        }

        fun findInProgressBatch(): SettlementBatchEntity? {
            return SettlementBatchEntity
                .find {
                    SettlementBatchTable.status.neq(SettlementBatchStatus.Completed)
                }
                .orderBy(SettlementBatchTable.sequenceId to SortOrder.ASC)
                .firstOrNull()
        }
    }

    fun allChainSettlementsPreparedOrCompleted() = this.chainBatches.all { it.isPreparedOrCompleted() }

    fun allChainSettlementsSubmittedOrCompleted() = this.chainBatches.all { it.isSubmittedOrCompleted() }

    fun allChainSettlementsRolledBackOrCompleted() = this.chainBatches.all { it.isRolledBackOrCompleted() }

    fun allChainSettlementsCompleted() = this.chainBatches.all { it.isCompleted() }

    fun settlingTrades() = this.trades.filter { it.settlementStatus == SettlementStatus.Settling }

    fun markAsPreparing() {
        this.status = SettlementBatchStatus.Submitted
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsSubmitted() {
        this.status = SettlementBatchStatus.Submitted
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsSubmitting() {
        this.status = SettlementBatchStatus.Submitting
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsCompleted() {
        this.status = SettlementBatchStatus.Completed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsRollingBack() {
        this.status = SettlementBatchStatus.RollingBack
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    var createdAt by SettlementBatchTable.createdAt
    var createdBy by SettlementBatchTable.createdBy
    var updatedAt by SettlementBatchTable.updatedAt
    var updatedBy by SettlementBatchTable.updatedBy
    var status by SettlementBatchTable.status
    var sequenceId by SettlementBatchTable.sequenceId
    val chainBatches by ChainSettlementBatchEntity referrersOn ChainSettlementBatchTable.settlementBatchGuid
    val trades by TradeEntity optionalReferrersOn TradeTable.settlementBatchGuid
}
