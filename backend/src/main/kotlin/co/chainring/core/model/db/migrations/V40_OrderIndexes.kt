package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V40_OrderIndexes : Migration() {

    override fun run() {
        transaction {
            exec("""CREATE INDEX order_wallet_guid_created_at_index ON "order" (wallet_guid, created_at)""")
        }
    }
}
