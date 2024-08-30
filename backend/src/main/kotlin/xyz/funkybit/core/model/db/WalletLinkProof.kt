package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class WalletLinkedProofId(override val value: String) : EntityId {
    companion object {
        fun generate(): WalletLinkedProofId = WalletLinkedProofId(TypeId.generate("walletlp").toString())
    }

    override fun toString(): String = value
}

object WalletLinkProofTable : GUIDTable<WalletLinkedProofId>("wallet_link_proof", ::WalletLinkedProofId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val message = varchar("message", 10485760)
    val signature = varchar("signature", 10485760)
}

class WalletLinkProofEntity(guid: EntityID<WalletLinkedProofId>) : GUIDEntity<WalletLinkedProofId>(guid) {
    companion object : EntityClass<WalletLinkedProofId, WalletLinkProofEntity>(WalletLinkProofTable) {
        fun create(wallet: WalletEntity, createdBy: WalletEntity, message: String, signature: String): WalletLinkProofEntity {
            return WalletLinkProofEntity.new(WalletLinkedProofId.generate()) {
                this.walletGuid = wallet.guid
                this.message = message
                this.signature = signature
                this.createdAt = Clock.System.now()
                this.createdBy = createdBy.address.toString()
            }
        }
    }

    var walletGuid by WalletLinkProofTable.walletGuid
    var wallet by WalletEntity referencedOn WalletLinkProofTable.walletGuid

    var message by WalletLinkProofTable.message
    var signature by WalletLinkProofTable.signature

    var createdAt by WalletLinkProofTable.createdAt
    var createdBy by WalletLinkProofTable.createdBy
}
