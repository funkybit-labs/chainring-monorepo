package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.Address

@Serializable
@JvmInline
value class WalletAuthorizationId(override val value: String) : EntityId {
    companion object {
        fun generate(): WalletAuthorizationId = WalletAuthorizationId(TypeId.generate("wauthorization").toString())
    }

    override fun toString(): String = value
}

object WalletAuthorizationTable : GUIDTable<WalletAuthorizationId>("wallet_authorization", ::WalletAuthorizationId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val message = varchar("message", 10485760)
    val signature = varchar("signature", 10485760)
}

class WalletAuthorizationEntity(guid: EntityID<WalletAuthorizationId>) : GUIDEntity<WalletAuthorizationId>(guid) {
    companion object : EntityClass<WalletAuthorizationId, WalletAuthorizationEntity>(WalletAuthorizationTable) {
        fun create(wallet: WalletEntity, createdBy: Address, message: String, signature: String): WalletAuthorizationEntity {
            return WalletAuthorizationEntity.new(WalletAuthorizationId.generate()) {
                this.walletGuid = wallet.guid
                this.message = message
                this.signature = signature
                this.createdAt = Clock.System.now()
                this.createdBy = createdBy.toString()
            }
        }
    }

    var walletGuid by WalletAuthorizationTable.walletGuid
    var wallet by WalletEntity referencedOn WalletAuthorizationTable.walletGuid

    var message by WalletAuthorizationTable.message
    var signature by WalletAuthorizationTable.signature

    var createdAt by WalletAuthorizationTable.createdAt
    var createdBy by WalletAuthorizationTable.createdBy
}
