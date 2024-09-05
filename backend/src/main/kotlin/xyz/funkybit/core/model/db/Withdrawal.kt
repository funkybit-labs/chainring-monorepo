package xyz.funkybit.core.model.db

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
import xyz.funkybit.core.evm.EIP712Transaction
import xyz.funkybit.core.evm.TokenAddressAndChain
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.toEvmSignature
import java.math.BigDecimal
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
    val fee = decimal("fee", 30, 0).default(BigDecimal.ZERO)
    val responseSequence = long("response_sequence").nullable().index()

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

        fun findByIdForUser(withdrawalId: WithdrawalId, userId: EntityID<UserId>): WithdrawalEntity? =
            WithdrawalTable
                .join(WalletTable, JoinType.INNER, WithdrawalTable.walletGuid, WalletTable.guid)
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where { WithdrawalTable.id.eq(withdrawalId).and(WalletTable.userGuid.eq(userId)) }
                .orderBy(Pair(WithdrawalTable.createdAt, SortOrder.DESC))
                .let(WithdrawalEntity::wrapRows)
                .singleOrNull()

        fun history(userId: EntityID<UserId>): List<WithdrawalEntity> =
            WithdrawalTable
                .join(WalletTable, JoinType.INNER, WithdrawalTable.walletGuid, WalletTable.guid)
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where { WalletTable.userGuid.eq(userId) }
                .orderBy(Pair(WithdrawalTable.createdAt, SortOrder.DESC))
                .let(WithdrawalEntity::wrapRows)
                .toList()

        fun findSequenced(chainId: ChainId, limit: Int, maxResponseSequence: Long?): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.Sequenced).and(SymbolTable.chainId.eq(chainId)).let { query ->
                        maxResponseSequence?.let { query.and(WithdrawalTable.responseSequence.less(it)) } ?: query
                    }
                }
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

    fun update(
        status: WithdrawalStatus,
        error: String?,
        blockchainTransactionEntity: BlockchainTransactionEntity? = null,
        actualAmount: BigInteger? = null,
        fee: BigInteger? = null,
        responseSequence: Long? = null,
    ) {
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
        fee?.let {
            this.fee = it
        }
        responseSequence?.let {
            this.responseSequence = responseSequence
        }
    }

    fun toEip712Transaction() = this.wallet.address.let {
        when (it) {
            is EvmAddress -> EIP712Transaction.WithdrawTx(
                it,
                TokenAddressAndChain(this.symbol.contractAddress ?: EvmAddress.zero, this.symbol.chainId.value),
                this.actualAmount ?: this.amount,
                this.nonce,
                this.amount == BigInteger.ZERO,
                this.signature,
                this.fee,
            )

            is BitcoinAddress -> TODO()
        }
    }

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

    var fee by WithdrawalTable.fee.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )

    var responseSequence by WithdrawalTable.responseSequence
}
