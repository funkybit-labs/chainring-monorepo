package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import java.math.BigInteger

@Serializable
@JvmInline
value class BitcoinUtxoId(override val value: String) : EntityId {
    init {
        require(value.split(":").size == 2) {
            "Invalid utxoId format"
        }
    }
    companion object {
        fun fromTxHashAndVout(txId: TxHash, vout: Int): BitcoinUtxoId =
            BitcoinUtxoId("${txId.value}:$vout")
    }

    fun txId(): TxHash = TxHash(value.split(":")[0])

    fun vout(): Long = value.split(":")[1].toLong()

    override fun toString(): String = value
}

enum class BitcoinUtxoStatus {
    Unspent,
    Spent,
    Reserved,
}

object BitcoinUtxoTable : GUIDTable<BitcoinUtxoId>("bitcoin_utxo", ::BitcoinUtxoId) {
    val addressGuid = reference("address_guid", BitcoinUtxoAddressMonitorTable).index()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()
    val spentByTxId = varchar("spent_by_tx_id", 10485760).nullable()
    val amount = decimal("amount", 30, 0)
    val status = customEnumeration(
        "status",
        "BitcoinUtxoStatus",
        { value -> BitcoinUtxoStatus.valueOf(value as String) },
        { PGEnum("BitcoinUtxoStatus", it) },
    ).index()
    val reservedBy = varchar("reserved_by", 10485760).nullable().index()
}

class BitcoinUtxoEntity(guid: EntityID<BitcoinUtxoId>) : GUIDEntity<BitcoinUtxoId>(guid) {
    companion object : EntityClass<BitcoinUtxoId, BitcoinUtxoEntity>(BitcoinUtxoTable) {
        fun createIfNotExist(bitcoinUtxoId: BitcoinUtxoId, address: BitcoinAddress, amount: Long): BitcoinUtxoEntity {
            return BitcoinUtxoEntity.findById(bitcoinUtxoId) ?: new(bitcoinUtxoId) {
                this.addressGuid = EntityID(BitcoinUtxoAddressMonitorId(address.value), BitcoinUtxoAddressMonitorTable)
                this.amount = amount.toBigInteger()
                this.createdAt = Clock.System.now()
                this.status = BitcoinUtxoStatus.Unspent
            }
        }

        fun findUnspentByAddress(address: BitcoinAddress): List<BitcoinUtxoEntity> {
            return BitcoinUtxoEntity.find {
                BitcoinUtxoTable.addressGuid.eq(BitcoinUtxoAddressMonitorId(address.value)) and
                    BitcoinUtxoTable.status.eq(BitcoinUtxoStatus.Unspent)
            }.toList()
        }

        fun findSpentForAddressAndTxId(address: BitcoinAddress, spentByTxId: TxHash): List<BitcoinUtxoEntity> {
            return BitcoinUtxoEntity.find {
                BitcoinUtxoTable.addressGuid.eq(BitcoinUtxoAddressMonitorId(address.value)) and
                    BitcoinUtxoTable.status.eq(BitcoinUtxoStatus.Spent) and
                    BitcoinUtxoTable.spentByTxId.eq(spentByTxId.value)
            }.toList()
        }

        fun findUnspentTotal(address: BitcoinAddress): BigInteger {
            return BitcoinUtxoTable.select(BitcoinUtxoTable.amount.sum()).where {
                BitcoinUtxoTable.addressGuid.eq(BitcoinUtxoAddressMonitorId(address.value)) and BitcoinUtxoTable.status.eq(BitcoinUtxoStatus.Unspent)
            }.map {
                it[BitcoinUtxoTable.amount.sum()]?.toBigInteger()
            }.firstOrNull() ?: BigInteger.ZERO
        }

        fun reserve(utxos: List<BitcoinUtxoEntity>, reservedBy: String) {
            val now = Clock.System.now()
            BitcoinUtxoTable.update(
                {
                    BitcoinUtxoTable.guid.inList(utxos.map { it.guid }) and BitcoinUtxoTable.status.eq(BitcoinUtxoStatus.Unspent)
                },
            ) {
                it[this.status] = BitcoinUtxoStatus.Reserved
                it[this.updatedAt] = now
                it[this.reservedBy] = reservedBy
            }
        }

        fun release(reservedBy: String) {
            val now = Clock.System.now()
            BitcoinUtxoTable.update(
                {
                    BitcoinUtxoTable.status.eq(BitcoinUtxoStatus.Reserved) and BitcoinUtxoTable.reservedBy.eq(reservedBy)
                },
            ) {
                it[this.status] = BitcoinUtxoStatus.Unspent
                it[this.reservedBy] = null
                it[this.updatedAt] = now
            }
        }

        fun spend(utxoIds: List<BitcoinUtxoId>, spentByTxId: TxHash) {
            val now = Clock.System.now()
            BitcoinUtxoTable.update({ BitcoinUtxoTable.guid.inList(utxoIds) }) {
                it[this.status] = BitcoinUtxoStatus.Spent
                it[this.spentByTxId] = spentByTxId.value
                it[this.updatedAt] = now
            }
        }
    }

    fun txId() = guid.value.txId()
    fun vout() = guid.value.vout()

    var addressGuid by BitcoinUtxoTable.addressGuid
    var spentByTxId by BitcoinUtxoTable.spentByTxId

    var amount by BitcoinUtxoTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var status by BitcoinUtxoTable.status
    var reservedBy by BitcoinUtxoTable.reservedBy
    var createdAt by BitcoinUtxoTable.createdAt
    var updatedAt by BitcoinUtxoTable.updatedAt
}
