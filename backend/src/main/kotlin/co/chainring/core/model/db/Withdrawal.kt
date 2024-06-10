package co.chainring.core.model.db

import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.toEvmSignature
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.math.BigInteger

@Serializable
@JvmInline
value class WithdrawalId(override val value: String) : EntityId {
    companion object {
        fun generate(): WithdrawalId = WithdrawalId(TypeId.generate("withdrawal").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class WithdrawalStatus {
    Pending,
    Sequenced,
    Settling,
    Complete,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(Complete, Failed)
    }
}

object WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val nonce = long("nonce")
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val symbolGuid = reference("symbol_guid", SymbolTable).index()
    val signature = varchar("signature", 10485760)
    val status = customEnumeration(
        "status",
        "WithdrawalStatus",
        { value -> WithdrawalStatus.valueOf(value as String) },
        { PGEnum("WithdrawalStatus", it) },
    ).index()
    val amount = decimal("amount", 30, 0)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val error = varchar("error", 10485760).nullable()
    val blockchainTransactionGuid = reference(
        "tx_guid",
        BlockchainTransactionTable,
    ).nullable()
    val sequenceId = long("sequence_id").autoIncrement()
    val transactionData = jsonb<EIP712Transaction>("transaction_data", KotlinxSerialization.json).nullable()
    val actualAmount = decimal("actual_amount", 30, 0).nullable()

    init {
        check("tx_when_settling") {
            status.neq(WithdrawalStatus.Settling).or(
                blockchainTransactionGuid.isNotNull().and(
                    transactionData.isNotNull(),
                ),
            )
        }
    }
}

class WithdrawalEntity(guid: EntityID<WithdrawalId>) : GUIDEntity<WithdrawalId>(guid) {
    companion object : EntityClass<WithdrawalId, WithdrawalEntity>(WithdrawalTable) {
        fun createPending(
            wallet: WalletEntity,
            symbol: SymbolEntity,
            amount: BigInteger,
            nonce: Long,
            signature: EvmSignature,
        ) = WithdrawalEntity.new(WithdrawalId.generate()) {
            val now = Clock.System.now()
            this.wallet = wallet
            this.symbol = symbol
            this.amount = amount
            this.nonce = nonce
            this.signature = signature
            this.status = WithdrawalStatus.Pending
            this.createdAt = now
            this.createdBy = "system"
        }

        fun findPending(): List<WithdrawalEntity> {
            return WithdrawalEntity.find {
                WithdrawalTable.status.inList(listOf(WithdrawalStatus.Pending, WithdrawalStatus.Sequenced, WithdrawalStatus.Settling))
            }.toList()
        }

        fun findSettling(chainId: ChainId): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.Settling).and(
                        SymbolTable.chainId.eq(chainId),
                    )
                }
                .map { WithdrawalEntity.wrapRow(it) }
                .toList()
        }

        fun history(address: Address): List<WithdrawalEntity> {
            return WithdrawalEntity.wrapRows(
                WithdrawalTable.join(
                    WalletTable,
                    JoinType.INNER,
                    WithdrawalTable.walletGuid,
                    WalletTable.guid,
                ).join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid).selectAll().where {
                    WalletTable.address.eq(address.value)
                }.orderBy(Pair(WithdrawalTable.createdAt, SortOrder.DESC)),
            ).toList()
        }

        fun findSequenced(chainId: ChainId, limit: Int): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where { WithdrawalTable.status.eq(WithdrawalStatus.Sequenced).and(SymbolTable.chainId.eq(chainId)) }
                .orderBy(Pair(WithdrawalTable.sequenceId, SortOrder.ASC))
                .limit(limit)
                .map { WithdrawalEntity.wrapRow(it) }
                .toList()
        }

        fun updateToSettling(withdrawals: List<WithdrawalEntity>, blockchainTransactionEntity: BlockchainTransactionEntity, transactionDataByWithdrawalId: Map<WithdrawalId, EIP712Transaction>) {
            BatchUpdateStatement(WithdrawalTable).apply {
                withdrawals.forEach {
                    addBatch(it.id)
                    this[WithdrawalTable.status] = WithdrawalStatus.Settling
                    this[WithdrawalTable.blockchainTransactionGuid] = blockchainTransactionEntity.guid.value
                    transactionDataByWithdrawalId[it.id.value]?.let { txData ->
                        this[WithdrawalTable.transactionData] = txData
                    }
                }
                execute(TransactionManager.current())
            }
        }
    }

    fun update(status: WithdrawalStatus, error: String?, blockchainTransactionEntity: BlockchainTransactionEntity? = null, actualAmount: BigInteger? = null) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = status
        this.error = error
        blockchainTransactionEntity?.let {
            this.blockchainTransaction = it
        }
        actualAmount?.let {
            this.actualAmount = it
        }
    }

    fun toEip712Transaction() = EIP712Transaction.WithdrawTx(
        this.wallet.address,
        TokenAddressAndChain(this.symbol.contractAddress ?: Address.zero, this.symbol.chainId.value),
        this.actualAmount ?: this.amount,
        this.nonce,
        this.amount == BigInteger.ZERO,
        this.signature,
    )

    var nonce by WithdrawalTable.nonce
    var walletGuid by WithdrawalTable.walletGuid
    var wallet by WalletEntity referencedOn WithdrawalTable.walletGuid
    var symbolGuid by WithdrawalTable.symbolGuid
    var symbol by SymbolEntity referencedOn WithdrawalTable.symbolGuid

    var signature by WithdrawalTable.signature.transform(
        toColumn = { it.value },
        toReal = { it.toEvmSignature() },
    )
    var createdAt by WithdrawalTable.createdAt
    var createdBy by WithdrawalTable.createdBy
    var status by WithdrawalTable.status
    var amount by WithdrawalTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var updatedAt by WithdrawalTable.updatedAt
    var updatedBy by WithdrawalTable.updatedBy
    var error by WithdrawalTable.error

    var blockchainTransactionGuid by WithdrawalTable.blockchainTransactionGuid
    var blockchainTransaction by BlockchainTransactionEntity optionalReferencedOn WithdrawalTable.blockchainTransactionGuid

    var sequenceId by WithdrawalTable.sequenceId
    var transactionData by WithdrawalTable.transactionData

    var actualAmount by WithdrawalTable.actualAmount.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )
}
