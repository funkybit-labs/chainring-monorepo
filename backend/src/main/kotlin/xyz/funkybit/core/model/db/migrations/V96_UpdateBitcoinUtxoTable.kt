package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorId
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V96_UpdateBitcoinUtxoTable : Migration() {

    object V96_BitcoinUtxoAddressMonitorTable : GUIDTable<BitcoinUtxoAddressMonitorId>("bitcoin_utxo_address_monitor", ::BitcoinUtxoAddressMonitorId) {
        val createdAt = timestamp("created_at")
        val allowMempoolTxs = bool("allow_mempool_txs")
        val skipTxIds = array<String>("skip_txids", VarCharColumnType(10485760)).default(emptyList())
        val updatedAt = timestamp("updated_at").nullable()
        val lastSeenBlockHeight = long("last_seen_block_height").nullable()
        val isDepositAddress = bool("is_deposit_address").default(false)
    }

    object V96_BitcoinUtxoTable : GUIDTable<BitcoinUtxoId>("bitcoin_utxo", ::BitcoinUtxoId) {
        val spentByTxId = varchar("spent_by_tx_id", 10485760).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V96_BitcoinUtxoAddressMonitorTable, V96_BitcoinUtxoTable)

            exec("ALTER TABLE bitcoin_utxo DROP COLUMN created_by_block_guid")
            exec("ALTER TABLE bitcoin_utxo DROP COLUMN spent_by_block_guid")
        }
    }
}
