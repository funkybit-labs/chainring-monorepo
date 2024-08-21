package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BitcoinWalletStateId
import xyz.funkybit.core.model.db.BitcoinWalletStateTable.nullable
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V76_AddBlockHeightToBitcoinWalletState : Migration() {

    object V76_BitcoinWalletStateTable : GUIDTable<BitcoinWalletStateId>("bitcoin_wallet_state", ::BitcoinWalletStateId) {
        val lastSeenBlockHeight = long("last_seen_block_height").nullable()
    }

    override fun run() {
        transaction {
            exec(
                """
                    ALTER TABLE bitcoin_wallet_state DROP COLUMN last_tx_id
                """.trimIndent(),
            )
            SchemaUtils.createMissingTablesAndColumns(V76_BitcoinWalletStateTable)
        }
    }
}
