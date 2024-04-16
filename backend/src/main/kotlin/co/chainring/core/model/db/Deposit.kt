package co.chainring.core.model.db

import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.max
import java.math.BigInteger

@Serializable
@JvmInline
value class DepositId(override val value: String) : EntityId {
    companion object {
        fun generate(): DepositId = DepositId(TypeId.generate("deposit").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class DepositStatus {
    Pending,
    Complete,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(Complete, Failed)
    }
}

object DepositTable : GUIDTable<DepositId>("deposit", ::DepositId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val status = customEnumeration(
        "status",
        "DepositStatus",
        { value -> DepositStatus.valueOf(value as String) },
        { PGEnum("DepositStatus", it) },
    ).index()
    val symbolGuid = reference("symbol_guid", SymbolTable).index()
    val amount = decimal("amount", 30, 0)
    val blockNumber = decimal("block_number", 30, 0).index()
    val transactionHash = varchar("transaction_hash", 10485760).uniqueIndex()
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val error = varchar("error", 10485760).nullable()
}

class DepositEntity(guid: EntityID<DepositId>) : GUIDEntity<DepositId>(guid) {

    companion object : EntityClass<DepositId, DepositEntity>(DepositTable) {
        fun create(
            chainId: ChainId,
            wallet: WalletEntity,
            tokenAddress: Address?,
            amount: BigInteger,
            blockNumber: BigInteger,
            transactionHash: TxHash,
        ) = DepositEntity.new(DepositId.generate()) {
            val now = Clock.System.now()
            this.createdAt = now
            this.createdBy = "system"
            this.walletGuid = wallet.guid
            this.symbolGuid = SymbolEntity.forChainAndContractAddress(chainId, tokenAddress).guid
            this.status = DepositStatus.Pending
            this.amount = amount
            this.blockNumber = blockNumber
            this.transactionHash = transactionHash
        }
        fun findByTxHash(txHash: TxHash): DepositEntity? {
            return DepositEntity.find {
                DepositTable.transactionHash.eq(txHash.value)
            }.firstOrNull()
        }

        fun maxBlockNumber(): BigInteger? {
            return DepositTable
                .select(DepositTable.blockNumber.max())
                .maxByOrNull { DepositTable.blockNumber }
                ?.let { it[DepositTable.blockNumber.max()]?.toBigInteger() }
        }
    }

    fun update(status: DepositStatus, error: String? = null) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = status
        this.error = error
    }

    var walletGuid by DepositTable.walletGuid
    var wallet by WalletEntity referencedOn DepositTable.walletGuid

    var status by DepositTable.status

    var symbolGuid by DepositTable.symbolGuid
    var symbol by SymbolEntity referencedOn DepositTable.symbolGuid

    var amount by DepositTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )

    var blockNumber by DepositTable.blockNumber.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var transactionHash by DepositTable.transactionHash.transform(
        toReal = { TxHash(it) },
        toColumn = { it.value },
    )

    var createdAt by DepositTable.createdAt
    var createdBy by DepositTable.createdBy
    var updatedAt by DepositTable.updatedAt
    var updatedBy by DepositTable.updatedBy

    var error by DepositTable.error
}
