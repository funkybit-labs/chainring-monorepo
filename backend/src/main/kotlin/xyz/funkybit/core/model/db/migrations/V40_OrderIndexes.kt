package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V40_OrderIndexes : Migration() {

    override fun run() {
        transaction {
            exec("""CREATE INDEX order_wallet_guid_created_at_index ON "order" (wallet_guid, created_at)""")
        }
    }
}
