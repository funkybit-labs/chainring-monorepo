package co.chainring.core.model.db

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization.json
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigInteger

@JvmInline
value class BlockchainTransactionId(override val value: String) : EntityId {
    companion object {
        fun generate(): BlockchainTransactionId = BlockchainTransactionId(TypeId.generate("bctx").toString())
    }

    override fun toString(): String = value
}

enum class BlockchainTransactionStatus {
    Pending,
    Submitted,
    Confirmed,
    Completed,
    Failed,
}

@Serializable
@JvmInline
value class TxHash(val value: String)

@Serializable
data class BlockchainTransactionData(
    val data: String,
    val to: Address,
    val value: BigIntegerJson = BigInteger.ZERO,
)

object BlockchainTransactionTable : GUIDTable<BlockchainTransactionId>("blockchain_transaction", ::BlockchainTransactionId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val status = customEnumeration(
        "status",
        "BlockchainTransactionStatus",
        { value -> BlockchainTransactionStatus.valueOf(value as String) },
        { PGEnum("BlockchainTransactionStatus", it) },
    ).index()
    val error = varchar("error", 10485760).nullable()
    val sequenceId = integer("sequence_id").autoIncrement()
    val txHash = varchar("tx_hash", 10485760).nullable()
    val transactionData = jsonb<BlockchainTransactionData>("transaction_data", json)
    val chainId = reference("chain_id", ChainTable)
    val blockNumber = decimal("block_number", 30, 0).nullable()
    val gasAccountFee = decimal("gas_account_fee", 30, 0).nullable()
    val actualGas = decimal("actual_gas", 30, 0).nullable()
}

class BlockchainTransactionEntity(guid: EntityID<BlockchainTransactionId>) : GUIDEntity<BlockchainTransactionId>(guid) {
    companion object : EntityClass<BlockchainTransactionId, BlockchainTransactionEntity>(BlockchainTransactionTable) {
        fun create(
            chainId: ChainId,
            transactionData: BlockchainTransactionData,
        ): BlockchainTransactionEntity {
            val entity = BlockchainTransactionEntity.new(BlockchainTransactionId.generate()) {
                val now = Clock.System.now()
                this.createdAt = now
                this.createdBy = "system"
                this.status = BlockchainTransactionStatus.Pending
                this.transactionData = transactionData
                this.chainId = EntityID(chainId, ChainTable)
            }
            return entity
        }
    }

    fun markAsSeen(blockNumber: BigInteger) {
        this.blockNumber = blockNumber
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsSubmitted(txHash: TxHash) {
        this.txHash = txHash
        this.status = BlockchainTransactionStatus.Submitted
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsConfirmed(gasAccountFee: BigInteger?, gasUsed: BigInteger?) {
        this.status = BlockchainTransactionStatus.Confirmed
        this.gasAccountFee = gasAccountFee
        this.actualGas = gasUsed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsCompleted() {
        this.status = BlockchainTransactionStatus.Completed
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    fun markAsFailed(error: String, gasAccountFee: BigInteger? = null, gasUsed: BigInteger? = null) {
        this.status = BlockchainTransactionStatus.Failed
        this.gasAccountFee = gasAccountFee
        this.actualGas = gasUsed
        this.error = error
        this.updatedAt = Clock.System.now()
        this.updatedBy = "system"
    }

    var createdAt by BlockchainTransactionTable.createdAt
    var createdBy by BlockchainTransactionTable.createdBy
    var updatedAt by BlockchainTransactionTable.updatedAt
    var updatedBy by BlockchainTransactionTable.updatedBy
    var status by BlockchainTransactionTable.status
    var error by BlockchainTransactionTable.error
    var sequenceId by BlockchainTransactionTable.sequenceId
    var txHash by BlockchainTransactionTable.txHash.transform(
        toReal = { it?.let(::TxHash) },
        toColumn = { it?.value },
    )
    var transactionData by BlockchainTransactionTable.transactionData
    var blockNumber by BlockchainTransactionTable.blockNumber.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )
    var gasAccountFee by BlockchainTransactionTable.gasAccountFee.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )
    var actualGas by BlockchainTransactionTable.actualGas.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )

    var chainId by BlockchainTransactionTable.chainId
    var chain by ChainEntity referencedOn BlockchainTransactionTable.chainId
}
