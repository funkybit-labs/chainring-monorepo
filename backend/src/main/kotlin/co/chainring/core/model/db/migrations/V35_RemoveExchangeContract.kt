package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V35_RemoveExchangeContract : Migration() {

    override fun run() {
        transaction {
            // smart contract changes not backward compatible in this version
            exec("DELETE from deployed_smart_contract where name = 'Exchange'")
        }
    }
}
