package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V45_ChainSettlementBatchIndexes : Migration() {

    override fun run() {
        transaction {
            exec("CREATE INDEX chain_settlement_batch_chain_id ON chain_settlement_batch (chain_id)")
            exec("CREATE INDEX chain_settlement_batch_settlement_batch_guid ON chain_settlement_batch (settlement_batch_guid)")
        }
    }
}
