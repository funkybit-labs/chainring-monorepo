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
import org.jetbrains.exposed.sql.andIfNotNull
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Signature
import xyz.funkybit.core.model.evm.EIP712Transaction
import xyz.funkybit.core.model.evm.TokenAddressAndChain
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
    RollingBack,
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
    val archTransactionGuid = reference(
        "arch_tx_guid",
        BlockchainTransactionTable,
    ).nullable()
    val archRollbackTransactionGuid = reference(
        "arch_rollback_tx_guid",
        BlockchainTransactionTable,
    ).nullable()

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

data class ArchWithdrawalInfo(
    val withdrawalEntity: WithdrawalEntity,
    val archAccountEntity: ArchAccountEntity,
    val balanceIndex: Int,
    val address: BitcoinAddress,
)

class WithdrawalEntity(guid: EntityID<WithdrawalId>) : GUIDEntity<WithdrawalId>(guid) {
    companion object : EntityClass<WithdrawalId, WithdrawalEntity>(WithdrawalTable) {
        fun createPending(
            wallet: WalletEntity,
            symbol: SymbolEntity,
            amount: BigInteger,
            nonce: Long,
            signature: Signature,
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

        fun findSettlingOnArch(): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.Settling) and
                        SymbolTable.chainId.eq(bitcoinConfig.chainId) and
                        WithdrawalTable.archTransactionGuid.isNotNull() and
                        WithdrawalTable.blockchainTransactionGuid.isNull()
                }
                .map { WithdrawalEntity.wrapRow(it) }
                .toList()
        }

        fun findSettlingOnBitcoin(): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.Settling) and
                        SymbolTable.chainId.eq(bitcoinConfig.chainId) and
                        WithdrawalTable.blockchainTransactionGuid.isNotNull()
                }
                .map { WithdrawalEntity.wrapRow(it) }
                .toList()
        }

        fun findNeedsToInitiateRollbackOnArch(): List<ArchWithdrawalInfo> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .join(WalletTable, JoinType.INNER, WithdrawalTable.walletGuid, WalletTable.guid)
                .join(ArchAccountTable, JoinType.INNER, WithdrawalTable.symbolGuid, ArchAccountTable.symbolGuid)
                .join(ArchAccountBalanceIndexTable, JoinType.INNER, ArchAccountTable.guid, ArchAccountBalanceIndexTable.archAccountGuid, additionalConstraint = { ArchAccountBalanceIndexTable.walletGuid.eq(WithdrawalTable.walletGuid) })
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.RollingBack) and
                        SymbolTable.chainId.eq(bitcoinConfig.chainId) and
                        WithdrawalTable.archRollbackTransactionGuid.isNull()
                }
                .orderBy(Pair(WithdrawalTable.sequenceId, SortOrder.ASC))
                .map {
                    ArchWithdrawalInfo(
                        withdrawalEntity = WithdrawalEntity.wrapRow(it),
                        archAccountEntity = ArchAccountEntity.wrapRow(it),
                        balanceIndex = it[ArchAccountBalanceIndexTable.addressIndex],
                        address = BitcoinAddress.canonicalize(it[WalletTable.address]),
                    )
                }
        }

        fun updateToRollingBackOnArch(withdrawals: List<WithdrawalEntity>, archTransactionEntity: BlockchainTransactionEntity) {
            BatchUpdateStatement(WithdrawalTable).apply {
                withdrawals.forEach {
                    addBatch(it.id)
                    this[WithdrawalTable.status] = WithdrawalStatus.RollingBack
                    this[WithdrawalTable.archRollbackTransactionGuid] = archTransactionEntity.guid.value
                }
                execute(TransactionManager.current())
            }
        }

        fun findRollingBackOnArch(): List<WithdrawalEntity> {
            return WithdrawalTable
                .join(SymbolTable, JoinType.INNER, WithdrawalTable.symbolGuid, SymbolTable.guid)
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.RollingBack) and
                        SymbolTable.chainId.eq(bitcoinConfig.chainId) and
                        WithdrawalTable.archRollbackTransactionGuid.isNotNull()
                }
                .map { WithdrawalEntity.wrapRow(it) }
                .toList()
        }

        fun findSequencedArchWithdrawals(limit: Int, maxResponseSequence: Long?): List<ArchWithdrawalInfo> {
            return WithdrawalTable
                .join(WalletTable, JoinType.INNER, WithdrawalTable.walletGuid, WalletTable.guid)
                .join(ArchAccountTable, JoinType.INNER, WithdrawalTable.symbolGuid, ArchAccountTable.symbolGuid)
                .join(ArchAccountBalanceIndexTable, JoinType.INNER, ArchAccountTable.guid, ArchAccountBalanceIndexTable.archAccountGuid, additionalConstraint = { ArchAccountBalanceIndexTable.walletGuid.eq(WithdrawalTable.walletGuid) })
                .selectAll()
                .where {
                    WithdrawalTable.status.eq(WithdrawalStatus.Sequenced)
                        .and(ArchAccountBalanceIndexTable.status.eq(ArchAccountBalanceIndexStatus.Assigned))
                        .andIfNotNull(maxResponseSequence?.let { WithdrawalTable.responseSequence.less(it) })
                }
                .orderBy(Pair(WithdrawalTable.sequenceId, SortOrder.ASC))
                .limit(limit)
                .map {
                    ArchWithdrawalInfo(
                        withdrawalEntity = WithdrawalEntity.wrapRow(it),
                        archAccountEntity = ArchAccountEntity.wrapRow(it),
                        balanceIndex = it[ArchAccountBalanceIndexTable.addressIndex],
                        address = BitcoinAddress.canonicalize(it[WalletTable.address]),
                    )
                }
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

        fun updateToSettlingOnArch(withdrawals: List<WithdrawalEntity>, archTransactionEntity: BlockchainTransactionEntity) {
            BatchUpdateStatement(WithdrawalTable).apply {
                withdrawals.forEach {
                    addBatch(it.id)
                    this[WithdrawalTable.status] = WithdrawalStatus.Settling
                    this[WithdrawalTable.archTransactionGuid] = archTransactionEntity.guid.value
                }
                execute(TransactionManager.current())
            }
        }

        fun existsForUserAndNetworkType(user: UserEntity, networkType: NetworkType): Boolean {
            return WithdrawalTable.innerJoin(WalletTable).select(WithdrawalTable.id).where {
                WalletTable.networkType.eq(networkType) and WalletTable.userGuid.eq(user.guid)
            }.limit(1).any()
        }

        fun existsCompletedForWallet(wallet: WalletEntity): Boolean {
            return WithdrawalTable.select(WithdrawalTable.id).where {
                WithdrawalTable.walletGuid.eq(wallet.guid) and WithdrawalTable.status.eq(WithdrawalStatus.Complete)
            }.limit(1).any()
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

        if (status == WithdrawalStatus.Complete) {
            TestnetChallengeUserRewardEntity.createWithdrawalReward(this.wallet.user, this.wallet)
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
                this.signature as EvmSignature,
                this.fee,
            )

            is BitcoinAddress -> TODO()
        }
    }

    fun resolvedAmount(): BigInteger = actualAmount ?: amount
    fun chainAmount() = resolvedAmount() - fee

    var nonce by WithdrawalTable.nonce
    var walletGuid by WithdrawalTable.walletGuid
    var wallet by WalletEntity referencedOn WithdrawalTable.walletGuid
    var symbolGuid by WithdrawalTable.symbolGuid
    var symbol by SymbolEntity referencedOn WithdrawalTable.symbolGuid

    var signature by WithdrawalTable.signature.transform(
        toColumn = { it.value },
        toReal = { Signature.auto(it) },
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

    var archTransactionGuid by WithdrawalTable.archTransactionGuid
    var archTransaction by BlockchainTransactionEntity optionalReferencedOn WithdrawalTable.archTransactionGuid

    var archRollbackTransactionGuid by WithdrawalTable.archRollbackTransactionGuid
    var archRollbackTransaction by BlockchainTransactionEntity optionalReferencedOn WithdrawalTable.archRollbackTransactionGuid

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
