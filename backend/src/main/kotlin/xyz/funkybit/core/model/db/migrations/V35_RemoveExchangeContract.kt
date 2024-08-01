package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V35_RemoveExchangeContract : Migration() {

    override fun run() {
        transaction {
            // smart contract changes not backward compatible in this version
            exec("DELETE from deployed_smart_contract where name = 'Exchange'")
        }
    }
}
