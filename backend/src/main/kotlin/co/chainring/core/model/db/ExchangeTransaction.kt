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
    val blockchainTransactionGuid = reference("blockchain_transaction_guid", BlockchainTransactionTable).index().nullable()

    init {
        index(
            customIndexName = "exchange_transaction_pending_status",
            columns = arrayOf(status),
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

        fun createList(chainId: ChainId, transactions: List<EIP712Transaction>) {
            val now = Clock.System.now()
            ExchangeTransactionTable.batchInsert(transactions) { transaction ->
                this[ExchangeTransactionTable.guid] = ExchangeTransactionId.generate()
                this[ExchangeTransactionTable.createdAt] = now
                this[ExchangeTransactionTable.createdBy] = "system"
                this[ExchangeTransactionTable.transactionData] = transaction
                this[ExchangeTransactionTable.chainId] = chainId
                this[ExchangeTransactionTable.status] = ExchangeTransactionStatus.Pending
            }
        }

        fun assignToBlockchainTransaction(ids: List<ExchangeTransactionId>, tx: BlockchainTransactionEntity) {
            val now = Clock.System.now()
            ExchangeTransactionTable.update({
                ExchangeTransactionTable.guid.inList(ids)
            }) {
                it[this.status] = ExchangeTransactionStatus.Assigned
                it[this.blockchainTransactionGuid] = tx.guid
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

        fun findExchangeTransactionsForBlockchainTransaction(blockchainTransactionId: BlockchainTransactionId): List<ExchangeTransactionEntity> {
            return ExchangeTransactionEntity.find {
                ExchangeTransactionTable.blockchainTransactionGuid.eq(blockchainTransactionId)
            }.orderBy(ExchangeTransactionTable.sequenceId to SortOrder.ASC).toList()
        }
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

    var blockchainTransactionGuid by ExchangeTransactionTable.blockchainTransactionGuid
    var blockchainTransaction by BlockchainTransactionEntity optionalReferencedOn ExchangeTransactionTable.blockchainTransactionGuid
}
