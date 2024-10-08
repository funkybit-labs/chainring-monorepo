package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.bitcoinj.core.ECKey
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.EncryptedString
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.encrypt
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.schnorr.Point
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes

@Serializable
@JvmInline
value class ArchAccountId(override val value: String) : EntityId {
    companion object {
        fun generate(): ArchAccountId = ArchAccountId(TypeId.generate("archacct").toString())
    }

    override fun toString(): String = value
}

enum class ArchAccountType {
    Program,
    ProgramState,
    TokenState,
}

enum class ArchAccountStatus {
    Funded,
    Creating,
    Created,
    Initializing,
    Complete,
    Full,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(Complete, Failed)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class AccountSetupState {
    @Serializable
    @SerialName("program")
    data class Program(
        val deploymentTxIds: List<TxHash>,
        var numProcessed: Int,
        var executableTxId: TxHash?,
    ) : AccountSetupState()

    @Serializable
    @SerialName("program_state")
    data class ProgramState(
        var initializeTxId: TxHash,
    ) : AccountSetupState()

    @Serializable
    @SerialName("token_state")
    data class TokenState(
        val initializeTxId: TxHash,
    ) : AccountSetupState()
}

object ArchAccountTable : GUIDTable<ArchAccountId>("arch_account", ::ArchAccountId) {
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()
    val utxoId = varchar("utxo_id", 10485760).uniqueIndex()
    val creationTxId = varchar("creation_tx_id", 10485760).nullable()
    val encryptedPrivateKey = varchar("encrypted_private_key", 10485760)
    val publicKey = varchar("public_key", 10485760)
    val type = customEnumeration(
        "type",
        "ArchAccountType",
        { value -> ArchAccountType.valueOf(value as String) },
        { PGEnum("ArchAccountType", it) },
    ).index()
    val symbolGuid = reference("symbol_guid", SymbolTable).nullable().index()
    val setupState = jsonb<AccountSetupState>("setup_state", KotlinxSerialization.json).nullable()
    val status = customEnumeration(
        "status",
        "ArchAccountStatus",
        { value -> ArchAccountStatus.valueOf(value as String) },
        { PGEnum("ArchAccountStatus", it) },
    )
    val sequenceId = integer("sequence_id").autoIncrement()
}

class ArchAccountEntity(guid: EntityID<ArchAccountId>) : GUIDEntity<ArchAccountId>(guid) {
    companion object : EntityClass<ArchAccountId, ArchAccountEntity>(ArchAccountTable) {
        fun create(utxoId: BitcoinUtxoId, ecKey: ECKey, accountType: ArchAccountType, symbolEntity: SymbolEntity? = null): ArchAccountEntity {
            return ArchAccountEntity.new(ArchAccountId.generate()) {
                this.utxoId = utxoId
                this.type = accountType
                this.encryptedPrivateKey = ecKey.privateKeyAsHex.encrypt()
                this.publicKey = Point.genPubKey(ecKey.privKeyBytes).toHex(false)
                this.status = ArchAccountStatus.Funded
                this.createdAt = Clock.System.now()
                this.symbolGuid = symbolEntity?.guid
            }
        }

        fun findProgramAccount(): ArchAccountEntity? {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.Program)
            }.singleOrNull()
        }

        fun findProgramStateAccount(): ArchAccountEntity? {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.ProgramState)
            }.singleOrNull()
        }

        fun findTokenAccountForSymbolForNewIndex(symbolId: SymbolId): ArchAccountEntity? {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.TokenState) and
                    ArchAccountTable.symbolGuid.eq(symbolId) and
                    ArchAccountTable.status.inList(listOf(ArchAccountStatus.Full, ArchAccountStatus.Complete))
            }.orderBy(ArchAccountTable.sequenceId to SortOrder.DESC).firstOrNull()
        }

        fun findTokenAccountsForSymbol(symbolEntity: SymbolEntity): List<ArchAccountEntity> {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.TokenState) and
                    ArchAccountTable.symbolGuid.eq(symbolEntity.id)
            }.toList()
        }

        fun findAllTokenAccounts(): List<ArchAccountEntity> {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.TokenState)
            }.toList()
        }
        fun findAllInitializingTokenAccounts(): List<ArchAccountEntity> {
            return ArchAccountEntity.find {
                ArchAccountTable.type.eq(ArchAccountType.TokenState) and
                    ArchAccountTable.status.neq(ArchAccountStatus.Complete)
            }.toList()
        }

        fun getByPubkey(pubkey: ArchNetworkRpc.Pubkey): ArchAccountEntity? {
            return ArchAccountEntity.find {
                ArchAccountTable.publicKey.eq(pubkey.toHexString())
            }.firstOrNull()
        }
    }

    fun ecKey() = ECKey.fromPrivate(this.encryptedPrivateKey.decrypt().toHexBytes())

    fun markAsCreating(creationTxId: TxHash) {
        this.status = ArchAccountStatus.Creating
        this.creationTxId = creationTxId
        this.updatedAt = Clock.System.now()
    }

    fun markAsCreated() {
        this.status = ArchAccountStatus.Created
        this.updatedAt = Clock.System.now()
    }

    fun markAsComplete() {
        this.status = ArchAccountStatus.Complete
        this.updatedAt = Clock.System.now()
    }

    fun markAsFailed() {
        this.status = ArchAccountStatus.Failed
        this.updatedAt = Clock.System.now()
    }

    fun markAsInitializing(setupState: AccountSetupState) {
        this.status = ArchAccountStatus.Initializing
        this.setupState = setupState
        this.updatedAt = Clock.System.now()
    }

    fun markAsFull() {
        this.status = ArchAccountStatus.Full
        this.updatedAt = Clock.System.now()
    }

    fun rpcPubkey() = ArchNetworkRpc.Pubkey.fromHexString(this.publicKey)

    var utxoId by ArchAccountTable.utxoId.transform(
        toReal = { BitcoinUtxoId(it) },
        toColumn = { it.value },
    )
    var creationTxId by ArchAccountTable.creationTxId.transform(
        toReal = { it?.let { TxHash(it) } },
        toColumn = { it?.value },
    )
    var type by ArchAccountTable.type
    var status by ArchAccountTable.status
    var setupState by ArchAccountTable.setupState
    var publicKey by ArchAccountTable.publicKey
    var encryptedPrivateKey by ArchAccountTable.encryptedPrivateKey.transform(
        toReal = { EncryptedString(it) },
        toColumn = { it.encrypted },
    )

    var symbolGuid by ArchAccountTable.symbolGuid
    var symbol by SymbolEntity optionalReferencedOn ArchAccountTable.symbolGuid

    var createdAt by ArchAccountTable.createdAt
    var updatedAt by ArchAccountTable.updatedAt
}
