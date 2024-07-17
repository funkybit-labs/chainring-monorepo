package co.chainring.core.model.db

import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.web3j.crypto.Keys

@Serializable
@JvmInline
value class WalletLinkedSignerId(override val value: String) : EntityId {
    companion object {
        fun generate(): WalletLinkedSignerId = WalletLinkedSignerId(TypeId.generate("walletsigner").toString())
    }

    override fun toString(): String = value
}

@Serializable
data class WalletLinkedSigner(
    val walletAddress: Address,
    val chainId: ChainId,
    val signerAddress: Address,
)

object WalletLinkedSignerTable : GUIDTable<WalletLinkedSignerId>("wallet_linked_signer", ::WalletLinkedSignerId) {
    val walletGuid = reference("wallet_guid", WalletTable).index() // only one per wallet
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val chainId = reference("chain_id", ChainTable)
    val signerAddress = varchar("signer_address", 10485760)

    init {
        uniqueIndex(
            customIndexName = "uix_wallet_linked_signer_wallet_guid_chain_id",
            columns = arrayOf(walletGuid, chainId),
        )
    }
}

class WalletLinkedSignerEntity(guid: EntityID<WalletLinkedSignerId>) : GUIDEntity<WalletLinkedSignerId>(guid) {
    companion object : EntityClass<WalletLinkedSignerId, WalletLinkedSignerEntity>(WalletLinkedSignerTable) {
        fun createOrUpdate(wallet: WalletEntity, chainId: ChainId, signerAddress: Address): WalletLinkedSignerEntity {
            return findByWalletAndChain(wallet, chainId)?.let {
                it.signerAddress = signerAddress
                it.updatedAt = Clock.System.now()
                it.updatedBy = "system"
                it
            } ?: run {
                WalletLinkedSignerEntity.new(WalletLinkedSignerId.generate()) {
                    this.walletGuid = wallet.guid
                    this.signerAddress = signerAddress
                    this.chainId = EntityID(chainId, ChainTable)
                    this.createdAt = Clock.System.now()
                    this.createdBy = "system"
                }
            }
        }

        fun findByWalletAndChain(wallet: WalletEntity, chainId: ChainId): WalletLinkedSignerEntity? {
            return WalletLinkedSignerEntity.find {
                WalletLinkedSignerTable.walletGuid.eq(wallet.guid) and WalletLinkedSignerTable.chainId.eq(chainId)
            }.firstOrNull()
        }

        fun findForId(guid: WalletLinkedSignerId): WalletLinkedSigner? {
            return WalletLinkedSignerEntity.findById(guid)?.let {
                WalletLinkedSigner(it.wallet.address, it.chainId.value, it.signerAddress)
            }
        }

        fun findAll(): List<WalletLinkedSigner> {
            return WalletLinkedSignerTable
                .join(WalletTable, JoinType.INNER, WalletTable.guid, WalletLinkedSignerTable.walletGuid)
                .selectAll()
                .map {
                    WalletLinkedSigner(Address(it[WalletTable.address]), it[WalletLinkedSignerTable.chainId].value, Address(it[WalletLinkedSignerTable.signerAddress]))
                }
        }
    }

    var walletGuid by WalletLinkedSignerTable.walletGuid
    var wallet by WalletEntity referencedOn WalletLinkedSignerTable.walletGuid

    var signerAddress by WalletLinkedSignerTable.signerAddress.transform(
        toReal = { Address(Keys.toChecksumAddress(it)) },
        toColumn = { it.value },
    )

    var chainId by WalletLinkedSignerTable.chainId

    var createdAt by WalletLinkedSignerTable.createdAt
    var createdBy by WalletLinkedSignerTable.createdBy
    var updatedAt by WalletLinkedSignerTable.updatedAt
    var updatedBy by WalletLinkedSignerTable.updatedBy
}
