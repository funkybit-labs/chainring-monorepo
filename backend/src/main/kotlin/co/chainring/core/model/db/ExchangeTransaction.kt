package co.chainring.core.model.db

import co.chainring.core.evm.EIP712Transaction
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.update

@JvmInline
value class ExchangeTransactionId(override val value: String) : EntityId {
    companion object {
        fun generate(): ExchangeTransactionId = ExchangeTransactionId(TypeId.generate("bctx").toString())
    }

    override fun toString(): String = value
}

enum class ExchangeTransactionStatus {
    Pending,
    Assigned,
    Failed,
    Completed,
}

object ExchangeTransactionTable : GUIDTable<ExchangeTransactionId>("exchange_transaction", ::ExchangeTransactionId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val sequenceId = integer("sequence_id").autoIncrement()
    val chainId = reference("chain_id", ChainTable)
    val transactionData = jsonb<EIP712Transaction>("transaction_data", KotlinxSerialization.json)
    val status = ExchangeTransactionTable.customEnumeration(
        "status",
        "ExchangeTransactionStatus",
        { value -> ExchangeTransactionStatus.valueOf(value as String) },
        { PGEnum("ExchangeTransactionStatus", it) },
    )
    val exchangeTransactionBatchGuid = reference(
        "exchange_transaction_batch_guid",
        ExchangeTransactionBatchTable,
    ).index().nullable()
    val error = varchar("error", 10485760).nullable()

    init {
        index(
            customIndexName = "exchange_transaction_pending_status",
            columns = arrayOf(status),
            filterCondition = {
                status.eq(ExchangeTransactionStatus.Pending)
            },
        )

        index(
            customIndexName = "exchange_transaction_sequencer_chain_pending_status",
            columns = arrayOf(sequenceId, chainId, status),
            filterCondition = {
                status.eq(ExchangeTransactionStatus.Pending)
            },
        )
    }
}

class ExchangeTransactionEntity(guid: EntityID<ExchangeTransactionId>) : GUIDEntity<ExchangeTransactionId>(guid) {

    companion object : EntityClass<ExchangeTransactionId, ExchangeTransactionEntity>(ExchangeTransactionTable) {

        fun create(chainId: ChainId, transaction: EIP712Transaction) {
            val now = Clock.System.now()
            ExchangeTransactionEntity.new(ExchangeTransactionId.generate()) {
                this.createdAt = now
                this.createdBy = "system"
                this.transactionData = transaction
                this.chainId = EntityID(chainId, ChainTable)
                this.status = ExchangeTransactionStatus.Pending
            }
        }

        fun createList(transactions: List<EIP712Transaction>) {
            val now = Clock.System.now()
            ExchangeTransactionTable.batchInsert(transactions) { transaction ->
                this[ExchangeTransactionTable.guid] = ExchangeTransactionId.generate()
                this[ExchangeTransactionTable.createdAt] = now
                this[ExchangeTransactionTable.createdBy] = "system"
                this[ExchangeTransactionTable.transactionData] = transaction
                this[ExchangeTransactionTable.chainId] = transaction.getChainId()
                this[ExchangeTransactionTable.status] = ExchangeTransactionStatus.Pending
            }
        }

        fun assignToBatch(ids: List<ExchangeTransactionId>, batch: ExchangeTransactionBatchEntity) {
            val now = Clock.System.now()
            ExchangeTransactionTable.update({
                ExchangeTransactionTable.guid.inList(ids)
            }) {
                it[this.status] = ExchangeTransactionStatus.Assigned
                it[this.exchangeTransactionBatchGuid] = batch.guid
                it[this.updatedAt] = now
                it[this.updatedBy] = "system"
            }
        }

        fun findUnassignedExchangeTransactions(chainId: ChainId, limit: Int): List<ExchangeTransactionEntity> {
            return ExchangeTransactionEntity.find {
                ExchangeTransactionTable.status.eq(ExchangeTransactionStatus.Pending) and
                    ExchangeTransactionTable.chainId.eq(chainId)
            }.orderBy(ExchangeTransactionTable.sequenceId to SortOrder.ASC).limit(limit).toList()
        }

        fun findAssignedExchangeTransactionsForBatch(batchEntity: ExchangeTransactionBatchEntity): List<ExchangeTransactionEntity> {
            return ExchangeTransactionEntity.find {
                ExchangeTransactionTable.exchangeTransactionBatchGuid.eq(batchEntity.guid) and
                    ExchangeTransactionTable.status.eq(ExchangeTransactionStatus.Assigned)
            }.orderBy(ExchangeTransactionTable.sequenceId to SortOrder.ASC).toList()
        }

        fun findForBatchAndSequence(batchEntity: ExchangeTransactionBatchEntity, sequenceId: Int): ExchangeTransactionEntity? {
            return ExchangeTransactionEntity.find {
                ExchangeTransactionTable.exchangeTransactionBatchGuid.eq(batchEntity.guid) and
                    ExchangeTransactionTable.sequenceId.eq(sequenceId)
            }.firstOrNull()
        }

        fun markAsCompleted(batch: ExchangeTransactionBatchEntity) {
            val now = Clock.System.now()
            ExchangeTransactionTable.update({
                ExchangeTransactionTable.exchangeTransactionBatchGuid.eq(batch.guid) and
                    ExchangeTransactionTable.status.eq(ExchangeTransactionStatus.Assigned)
            }) {
                it[this.status] = ExchangeTransactionStatus.Completed
                it[this.updatedAt] = now
                it[this.updatedBy] = "system"
            }
        }
    }

    fun markAsFailed(error: String) {
        this.status = ExchangeTransactionStatus.Failed
        this.error = error
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    var createdAt by ExchangeTransactionTable.createdAt
    var createdBy by ExchangeTransactionTable.createdBy
    var updatedAt by ExchangeTransactionTable.updatedAt
    var updatedBy by ExchangeTransactionTable.updatedBy
    var sequenceId by ExchangeTransactionTable.sequenceId
    var status by ExchangeTransactionTable.status
    var transactionData by ExchangeTransactionTable.transactionData

    var chainId by ExchangeTransactionTable.chainId
    var chain by ChainEntity referencedOn ExchangeTransactionTable.chainId

    var error by ExchangeTransactionTable.error

    var exchangeTransactionBatchGuid by ExchangeTransactionTable.exchangeTransactionBatchGuid
    var exchangeTransactionBatch by ExchangeTransactionBatchEntity optionalReferencedOn ExchangeTransactionTable.exchangeTransactionBatchGuid
}
