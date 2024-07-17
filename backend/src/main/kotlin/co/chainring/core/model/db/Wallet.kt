package co.chainring.core.model.db

import co.chainring.core.model.Address
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.toChecksumAddress
import co.chainring.core.sequencer.toSequencerId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.web3j.crypto.Keys
import java.lang.RuntimeException

@Serializable
@JvmInline
value class WalletId(override val value: String) : EntityId {
    companion object {
        fun generate(address: Address): WalletId = WalletId("wallet_${address.value}")
    }

    override fun toString(): String = value
}

object WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val address = varchar("address", 10485760).uniqueIndex()
    val sequencerId = long("sequencer_id").uniqueIndex()
    val addedSymbols = array<String>("added_symbols", VarCharColumnType(10485760)).default(emptyList())
    val isAdmin = bool("is_admin").default(false)
}

class WalletEntity(guid: EntityID<WalletId>) : GUIDEntity<WalletId>(guid) {

    companion object : EntityClass<WalletId, WalletEntity>(WalletTable) {
        fun getOrCreate(address: Address): WalletEntity {
            return findByAddress(address) ?: run {
                WalletEntity.new(WalletId.generate(address)) {
                    this.address = address
                    this.sequencerId = address.toSequencerId()
                    this.createdAt = Clock.System.now()
                    this.createdBy = "system"
                }
            }
        }

        fun getByAddress(address: Address): WalletEntity =
            findByAddress(address) ?: throw RuntimeException("Wallet not found for address $address")

        fun findByAddress(address: Address): WalletEntity? {
            return WalletEntity.find {
                WalletTable.address.eq(address.toChecksumAddress().value)
            }.firstOrNull()
        }

        fun getBySequencerIds(sequencerIds: Set<SequencerWalletId>): List<WalletEntity> {
            return WalletEntity.find {
                WalletTable.sequencerId.inList(sequencerIds.map { it.value })
            }.toList()
        }

        fun getBySequencerId(sequencerId: SequencerWalletId): WalletEntity? {
            return WalletEntity.find {
                WalletTable.sequencerId.eq(sequencerId.value)
            }.firstOrNull()
        }
    }

    var createdAt by WalletTable.createdAt
    var createdBy by WalletTable.createdBy
    var address by WalletTable.address.transform(
        toReal = { Address(Keys.toChecksumAddress(it)) },
        toColumn = { it.value },
    )
    var sequencerId by WalletTable.sequencerId.transform(
        toReal = { SequencerWalletId(it) },
        toColumn = { it.value },
    )
    var addedSymbols by WalletTable.addedSymbols
    var isAdmin by WalletTable.isAdmin
}
