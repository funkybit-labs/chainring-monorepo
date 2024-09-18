package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress

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
}

class BitcoinUtxoAddressMonitorEntity(guid: EntityID<BitcoinUtxoAddressMonitorId>) : GUIDEntity<BitcoinUtxoAddressMonitorId>(guid) {
    companion object :
        EntityClass<BitcoinUtxoAddressMonitorId, BitcoinUtxoAddressMonitorEntity>(BitcoinUtxoAddressMonitorTable) {
        fun createIfNotExists(bitcoinAddress: BitcoinAddress): BitcoinUtxoAddressMonitorEntity {
            val id = BitcoinUtxoAddressMonitorId(bitcoinAddress.value)
            return findById(id) ?: new(id) {
                this.createdAt = Clock.System.now()
            }
        }

        fun getMonitoredAddresses(): List<BitcoinAddress> {
            return BitcoinUtxoAddressMonitorEntity.all().map {
                it.guid.value.toBitcoinAddress()
            }
        }
    }

    var createdAt by BitcoinUtxoAddressMonitorTable.createdAt
}
