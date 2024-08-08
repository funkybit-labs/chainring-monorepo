package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V32_Indexes : Migration() {

    override fun run() {
        transaction {
            // sql statements
            exec("CREATE INDEX order_execution_timestamp_index ON order_execution (timestamp)")
            exec("CREATE INDEX exchange_transaction_sequencer_chain_pending_status ON exchange_transaction (sequence_id, chain_id, status) WHERE (status = 'Pending'::exchangetransactionstatus)")
            exec("CREATE INDEX exchange_transaction_batch_sequencer_chain_status ON exchange_transaction_batch (sequence_id, chain_id, status) WHERE (status <> 'Completed'::exchangetransactionbatchstatus)")

            // foreign keys
            exec("CREATE INDEX exchange_transaction_batch_prepare_tx_guid ON exchange_transaction_batch (prepare_tx_guid)")
            exec("CREATE INDEX exchange_transaction_batch_submit_tx_guid ON exchange_transaction_batch (submit_tx_guid)")
            exec("CREATE INDEX balance_log_balance_guid ON balance_log (balance_guid)")
        }
    }
}
