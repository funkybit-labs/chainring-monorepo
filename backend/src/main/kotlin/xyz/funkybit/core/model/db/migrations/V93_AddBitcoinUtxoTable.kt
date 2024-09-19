package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorId
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.BlockHash
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V93_AddBitcoinUtxoTable : Migration() {

    object V93__BlockTable : GUIDTable<BlockHash>("block", ::BlockHash)

    enum class V93__BitcoinUtxoStatus {
        Unspent,
        Spent,
        Reserved,
    }

    object V93__BitcoinUtxoAddressMonitorTable : GUIDTable<BitcoinUtxoAddressMonitorId>("bitcoin_utxo_address_monitor", ::BitcoinUtxoAddressMonitorId) {
        val createdAt = timestamp("created_at")
    }

    object V93__BitcoinUtxoTable : GUIDTable<BitcoinUtxoId>("bitcoin_utxo", ::BitcoinUtxoId) {
        val addressGuid = reference("address_guid", V93__BitcoinUtxoAddressMonitorTable).index()
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()
        val createdByBlockGuid = reference("created_by_block_guid", V93__BlockTable).index()
        val spentByBlockGuid = reference("spent_by_block_guid", V93__BlockTable).nullable().index()
        val amount = long("amount")
        val status = customEnumeration(
            "status",
            "BitcoinUtxoStatus",
            { value -> V93__BitcoinUtxoStatus.valueOf(value as String) },
            { PGEnum("BitcoinUtxoStatus", it) },
        ).index()
        val reservedBy = varchar("reserved_by", 10485760).nullable().index()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE BitcoinUtxoStatus AS ENUM (${enumDeclaration<V93__BitcoinUtxoStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V93__BitcoinUtxoAddressMonitorTable, V93__BitcoinUtxoTable)

            exec("drop table bitcoin_wallet_state")
        }
    }
}
