package xyz.funkybit.core.model.db.migrations

import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BitcoinWalletStateId
import xyz.funkybit.core.model.db.BitcoinWalletStateTable.nullable
import xyz.funkybit.core.model.db.BitcoinWalletStateTable.uniqueIndex
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.UnspentUtxo

@Suppress("ClassName")
class V73_AddBitcoinWalletStateTable : Migration() {

    object V73_BitcoinWalletStateTable : GUIDTable<BitcoinWalletStateId>("bitcoin_wallet_state", ::BitcoinWalletStateId) {
        val address = varchar("address", 10485760).uniqueIndex()
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()
        val lastTxId = varchar("last_tx_id", 10485760).nullable()
        val unspentUtxos = jsonb<List<UnspentUtxo>>("unspent_utxos", KotlinxSerialization.json)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V73_BitcoinWalletStateTable)
        }
    }
}
