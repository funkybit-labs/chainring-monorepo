package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V76_AddBlockHeightToBitcoinWalletState : Migration() {

    @JvmInline
    value class V76_BitcoinWalletStateId(override val value: String) : EntityId

    object V76_BitcoinWalletStateTable : GUIDTable<V76_BitcoinWalletStateId>("bitcoin_wallet_state", ::V76_BitcoinWalletStateId) {
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
