package co.chainring.core.model.db

import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
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
    Confirmed,
    SentToSequencer,
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
    val blockNumber = decimal("block_number", 30, 0).nullable().index()
    val transactionHash = varchar("transaction_hash", 10485760).uniqueIndex()
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val error = varchar("error", 10485760).nullable()
    val canBeResubmitted = bool("can_be_resubmitted").default(false)
}

class DepositException(message: String) : Exception(message)

class DepositEntity(guid: EntityID<DepositId>) : GUIDEntity<DepositId>(guid) {
    companion object : EntityClass<DepositId, DepositEntity>(DepositTable) {
        fun findByTxHash(txHash: TxHash): DepositEntity? {
            return DepositEntity.find {
                DepositTable.transactionHash.eq(txHash.value)
            }.firstOrNull()
        }

        fun maxBlockNumber(chainId: ChainId): BigInteger? {
            return DepositTable
                .leftJoin(SymbolTable)
                .select(DepositTable.blockNumber.max())
                .where { SymbolTable.chainId.eq(chainId) }
                .maxByOrNull { DepositTable.blockNumber }
                ?.let { it[DepositTable.blockNumber.max()]?.toBigInteger() }
        }

        fun getPendingForUpdate(chainId: ChainId): List<DepositEntity> =
            getForUpdate(chainId, DepositStatus.Pending)

        fun getConfirmedForUpdate(chainId: ChainId): List<DepositEntity> =
            getForUpdate(chainId, DepositStatus.Confirmed)

        private fun getForUpdate(chainId: ChainId, status: DepositStatus): List<DepositEntity> =
            DepositTable
                .join(SymbolTable, JoinType.INNER, SymbolTable.guid, DepositTable.symbolGuid)
                .select(DepositTable.columns)
                .where { SymbolTable.chainId.eq(chainId) and DepositTable.status.eq(status) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = null, DepositTable))
                .let { DepositEntity.wrapRows(it) }
                .toList()

        fun history(address: Address): List<DepositEntity> {
            return DepositEntity.wrapRows(
                DepositTable.join(
                    WalletTable,
                    JoinType.INNER,
                    DepositTable.walletGuid,
                    WalletTable.guid,
                ).join(SymbolTable, JoinType.INNER, DepositTable.symbolGuid, SymbolTable.guid).selectAll().where {
                    WalletTable.address.eq(address.value)
                }.orderBy(Pair(DepositTable.createdAt, SortOrder.DESC)),
            ).toList()
        }

        fun createOrUpdate(
            wallet: WalletEntity,
            symbol: SymbolEntity,
            amount: BigIntegerJson,
            blockNumber: BigInteger?,
            transactionHash: TxHash,
        ): DepositEntity? {
            DepositTable.upsert(
                DepositTable.transactionHash,
                onUpdate = listOfNotNull(
                    DepositTable.walletGuid to stringLiteral(wallet.guid.value.value),
                    DepositTable.symbolGuid to stringLiteral(symbol.guid.value.value),
                    DepositTable.amount to decimalLiteral(amount.toBigDecimal()),
                    blockNumber?.let {
                        DepositTable.blockNumber to decimalLiteral(it.toBigDecimal())
                    },
                    DepositTable.transactionHash to stringLiteral(transactionHash.value),
                ),
            ) {
                it[DepositTable.guid] = DepositId.generate()
                it[createdAt] = Clock.System.now()
                it[createdBy] = wallet.address.value
                it[status] = DepositStatus.Pending
                it[walletGuid] = wallet.guid
                it[symbolGuid] = symbol.guid
                it[DepositTable.amount] = amount.toBigDecimal()
                it[DepositTable.blockNumber] = blockNumber?.toBigDecimal()
                it[DepositTable.transactionHash] = transactionHash.value
            }

            return findByTxHash(transactionHash)?.also {
                if (it.status == DepositStatus.Failed && it.canBeResubmitted) {
                    it.status = DepositStatus.Pending
                    it.createdAt = Clock.System.now()
                    it.updatedAt = it.createdAt
                }
            }
        }

        fun countConfirmedOrCompleted(blockNumbers: List<BigInteger>, chainId: ChainId): Long =
            DepositTable
                .leftJoin(SymbolTable)
                .selectAll()
                .where { DepositTable.blockNumber.inList(blockNumbers.map { it.toBigDecimal() }) }
                .andWhere { SymbolTable.chainId.eq(chainId) }
                .andWhere { DepositTable.status.inList(listOf(DepositStatus.Confirmed, DepositStatus.Complete)) }
                .count()

        fun markAsFailedByBlockNumbers(blockNumbers: List<BigInteger>, chainId: ChainId, error: String) {
            DepositTable
                .leftJoin(SymbolTable)
                .update({
                    DepositTable.blockNumber.inList(blockNumbers.map { it.toBigDecimal() })
                        .and(SymbolTable.chainId.eq(chainId))
                }) {
                    it[DepositTable.status] = DepositStatus.Failed
                    it[DepositTable.error] = error
                }
        }
    }

    fun updateBlockNumber(blockNumber: BigInteger) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.blockNumber = blockNumber
    }

    fun markAsConfirmed() {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = DepositStatus.Confirmed
    }

    fun markAsFailed(error: String, canBeResubmitted: Boolean = false) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = DepositStatus.Failed
        this.error = error
        this.canBeResubmitted = canBeResubmitted
    }

    fun markAsComplete() {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = DepositStatus.Complete
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
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
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
    var canBeResubmitted by DepositTable.canBeResubmitted
}
