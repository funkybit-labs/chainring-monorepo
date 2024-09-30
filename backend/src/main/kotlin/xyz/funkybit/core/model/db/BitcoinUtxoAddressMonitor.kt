package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.WalletTable.default

@JvmInline
value class BitcoinUtxoAddressMonitorId(override val value: String) : EntityId {
    init {
        require(Address.auto(value) is BitcoinAddress) {
            "Invalid address format"
        }
    }

    fun toBitcoinAddress() = Address.auto(value) as BitcoinAddress

    override fun toString(): String = value
}

object BitcoinUtxoAddressMonitorTable : GUIDTable<BitcoinUtxoAddressMonitorId>("bitcoin_utxo_address_monitor", ::BitcoinUtxoAddressMonitorId) {
    val createdAt = timestamp("created_at")
    val allowMempoolTxs = bool("allow_mempool_txs")
    val skipTxIds = array<String>("skip_txids", VarCharColumnType(10485760)).default(emptyList())
    val updatedAt = timestamp("updated_at").nullable()
    val lastSeenBlockHeight = long("last_seen_block_height").nullable()
    val isDepositAddress = bool("is_deposit_address").default(false)
}

class BitcoinUtxoAddressMonitorEntity(guid: EntityID<BitcoinUtxoAddressMonitorId>) : GUIDEntity<BitcoinUtxoAddressMonitorId>(guid) {
    companion object :
        EntityClass<BitcoinUtxoAddressMonitorId, BitcoinUtxoAddressMonitorEntity>(BitcoinUtxoAddressMonitorTable) {
        fun createIfNotExists(bitcoinAddress: BitcoinAddress, allowMempoolTxs: Boolean = true, skipTxIds: List<String> = listOf(), isDepositAddress: Boolean = false): BitcoinUtxoAddressMonitorEntity {
            val id = BitcoinUtxoAddressMonitorId(bitcoinAddress.value)
            return findById(id) ?: new(id) {
                this.allowMempoolTxs = allowMempoolTxs
                this.skipTxIds = skipTxIds
                this.createdAt = Clock.System.now()
                this.isDepositAddress = isDepositAddress
            }
        }
    }

    fun updateLastSeenBlockHeight(lastSeenBlockHeight: Long) {
        this.lastSeenBlockHeight = lastSeenBlockHeight
        this.updatedAt = Clock.System.now()
    }

    var allowMempoolTxs by BitcoinUtxoAddressMonitorTable.allowMempoolTxs
    var skipTxIds by BitcoinUtxoAddressMonitorTable.skipTxIds
    var isDepositAddress by BitcoinUtxoAddressMonitorTable.isDepositAddress
    var createdAt by BitcoinUtxoAddressMonitorTable.createdAt
    var updatedAt by BitcoinUtxoAddressMonitorTable.updatedAt
    var lastSeenBlockHeight by BitcoinUtxoAddressMonitorTable.lastSeenBlockHeight
}
