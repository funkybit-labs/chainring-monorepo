package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization.json
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.apps.api.model.BigIntegerJson

@Serializable
@JvmInline
value class BitcoinWalletStateId(override val value: String) : EntityId {
    companion object {
        fun generate(): BitcoinWalletStateId = BitcoinWalletStateId(TypeId.generate("btcwalletstate").toString())
    }

    override fun toString(): String = value
}

@Serializable
data class UnspentUtxo(
    val utxoId: String,
    val amount: BigIntegerJson,
    val blockHeight: Long?,
)

object BitcoinWalletStateTable : GUIDTable<BitcoinWalletStateId>("bitcoin_wallet_state", ::BitcoinWalletStateId) {
    val address = varchar("address", 10485760).uniqueIndex()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()
    val lastTxId = varchar("last_tx_id", 10485760).nullable()
    val unspentUtxos = jsonb<List<UnspentUtxo>>("unspent_utxos", json)
}

class BitcoinWalletStateEntity(guid: EntityID<BitcoinWalletStateId>) : GUIDEntity<BitcoinWalletStateId>(guid) {
    companion object : EntityClass<BitcoinWalletStateId, BitcoinWalletStateEntity>(BitcoinWalletStateTable) {
        fun create(address: String, lastTxId: String?, unspentUtxos: List<UnspentUtxo>): BitcoinWalletStateEntity {
            return BitcoinWalletStateEntity.new(BitcoinWalletStateId.generate()) {
                this.address = address
                this.unspentUtxos = unspentUtxos
                this.lastTxId = lastTxId
                this.createdAt = Clock.System.now()
            }
        }

        fun findByAddress(address: String): BitcoinWalletStateEntity? {
            return BitcoinWalletStateEntity.find {
                BitcoinWalletStateTable.address.eq(address)
            }.firstOrNull()
        }
    }

    fun update(lastTxId: String?, unspentUtxos: List<UnspentUtxo>) {
        this.lastTxId = lastTxId
        this.unspentUtxos = unspentUtxos
        this.updatedAt = Clock.System.now()
    }

    var address by BitcoinWalletStateTable.address

    var unspentUtxos by BitcoinWalletStateTable.unspentUtxos
    var lastTxId by BitcoinWalletStateTable.lastTxId

    var createdAt by BitcoinWalletStateTable.createdAt
    var updatedAt by BitcoinWalletStateTable.updatedAt
}
