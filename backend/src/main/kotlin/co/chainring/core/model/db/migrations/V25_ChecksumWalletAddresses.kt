package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.Address
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.WalletId
import co.chainring.core.sequencer.toSequencerId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.web3j.crypto.Keys

@Suppress("ClassName")
class V25_ChecksumWalletAddresses : Migration() {

    object V25_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val address = varchar("address", 10485760).uniqueIndex()
        val sequencerId = long("sequencer_id").uniqueIndex()
    }

    override fun run() {
        try {
            transaction {
                // checksum wallet address
                V25_WalletTable.selectAll().forEach { resultRow ->
                    val guid = resultRow[V25_WalletTable.guid]
                    val address = resultRow[V25_WalletTable.address]
                    println("Updating address $address $guid ")
                    V25_WalletTable.update({ V25_WalletTable.guid.eq(guid) }) {
                        val checksumAddress = Address(Keys.toChecksumAddress(address))
                        it[this.address] = Keys.toChecksumAddress(address)
                        it[this.sequencerId] = checksumAddress.toSequencerId().value
                    }
                }
            }
        } catch (e: Exception) {
            println("failed")
        }
    }
}
