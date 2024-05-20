package co.chainring.core.model.db

import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.toEvmSignature
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
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

        fun findPendingByWalletAndNonce(wallet: WalletEntity, nonce: Long): WithdrawalEntity? {
            return WithdrawalEntity.find {
                WithdrawalTable.walletGuid.eq(wallet.guid) and WithdrawalTable.nonce.eq(nonce) and WithdrawalTable.status.eq(WithdrawalStatus.Pending)
            }.firstOrNull()
        }

        fun findPending(): List<WithdrawalEntity> {
            return WithdrawalEntity.find {
                WithdrawalTable.status.eq(WithdrawalStatus.Pending)
            }.toList()
        }

        fun history(): List<WithdrawalEntity> {
            return WithdrawalEntity
                .all()
                .with(WithdrawalEntity::symbol)
                .orderBy(Pair(WithdrawalTable.createdAt, SortOrder.DESC))
                .toList()
        }
    }

    fun update(status: WithdrawalStatus, error: String?) {
        val now = Clock.System.now()
        this.updatedAt = now
        this.status = status
        this.error = error
    }

    fun toEip712Transaction() = EIP712Transaction.WithdrawTx(
        this.wallet.address,
        TokenAddressAndChain(this.symbol.contractAddress ?: Address.zero, this.symbol.chainId.value),
        this.amount,
        this.nonce,
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
}
