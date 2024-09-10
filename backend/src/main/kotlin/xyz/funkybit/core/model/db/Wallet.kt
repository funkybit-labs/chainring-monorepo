package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.SequencerWalletId
import xyz.funkybit.core.sequencer.toSequencerId
import java.lang.RuntimeException

@Serializable
@JvmInline
value class WalletId(override val value: String) : EntityId {
    companion object {
        fun generate(address: Address): WalletId = WalletId("wallet_$address")
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
    val networkType = customEnumeration(
        "network_type",
        "NetworkType",
        { value -> NetworkType.valueOf(value as String) },
        { PGEnum("NetworkType", it) },
    )
    val userGuid = reference("user_guid", UserTable).index()

    init {
        uniqueIndex(
            customIndexName = "wallet_user_network_type",
            columns = arrayOf(userGuid, networkType),
        )
    }
}

class WalletEntity(guid: EntityID<WalletId>) : GUIDEntity<WalletId>(guid) {
    companion object : EntityClass<WalletId, WalletEntity>(WalletTable) {
        fun getOrCreateWithUser(address: Address): WalletEntity {
            val canonicalAddress = address.canonicalize()
            return findByAddress(canonicalAddress)
                ?: run {
                    val user = UserEntity.create(canonicalAddress)
                    createForUser(user, address)
                }
        }

        fun createForUser(user: UserEntity, address: Address): WalletEntity {
            val canonicalAddress = address.canonicalize()
            return WalletEntity.new(WalletId.generate(canonicalAddress)) {
                this.address = canonicalAddress
                sequencerId = canonicalAddress.toSequencerId()
                createdAt = Clock.System.now()
                createdBy = "system"

                networkType = when (canonicalAddress) {
                    is EvmAddress -> NetworkType.Evm
                    is BitcoinAddress -> NetworkType.Bitcoin
                }
                this.user = user
            }
        }

        fun getByAddress(address: Address): WalletEntity =
            findByAddress(address) ?: throw RuntimeException("Wallet not found for address $address")

        fun findByAddress(address: Address): WalletEntity? {
            return WalletEntity.find {
                WalletTable.address.eq(
                    when (address) {
                        is EvmAddress -> address.canonicalize().value
                        is BitcoinAddress -> address.canonicalize().value
                    },
                )
            }.firstOrNull()
        }

        fun getBySequencerId(sequencerId: SequencerWalletId): WalletEntity? {
            return WalletEntity.find {
                WalletTable.sequencerId.eq(sequencerId.value)
            }.firstOrNull()
        }

        fun getAdminAddresses(): List<EvmAddress> {
            return WalletTable.select(listOf(WalletTable.address)).where {
                WalletTable.isAdmin.eq(true)
            }.map {
                EvmAddress(it[WalletTable.address])
            }
        }
    }

    fun authorizedAddresses(): List<Address> {
        return WalletTable
            .selectAll()
            .where {
                WalletTable.userGuid.eq(userGuid) and WalletTable.guid.neq(guid)
            }.map {
                Address.auto(it[WalletTable.address])
            }
    }

    var createdAt by WalletTable.createdAt
    var createdBy by WalletTable.createdBy
    var address by WalletTable.address.transform(
        toReal = { Address.auto(it) },
        toColumn = {
            when (it) {
                is EvmAddress -> it.value
                is BitcoinAddress -> it.value
            }
        },
    )
    var sequencerId by WalletTable.sequencerId.transform(
        toReal = { SequencerWalletId(it) },
        toColumn = { it.value },
    )
    var addedSymbols by WalletTable.addedSymbols
    var isAdmin by WalletTable.isAdmin

    var networkType by WalletTable.networkType
    var userGuid by WalletTable.userGuid
    var user by UserEntity referencedOn WalletTable.userGuid
}
