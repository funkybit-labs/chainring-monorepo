package xyz.funkybit.core.model.db.migrations

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.sequencer.toSequencerId

@Suppress("ClassName")
class V15_WalletTable : Migration() {

    object V15_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val address = varchar("address", 10485760).uniqueIndex()
        val sequencerId = long("sequencer_id").uniqueIndex()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
    }

    object V15_WithdrawalTable : GUIDTable<WithdrawalId>("withdrawal", ::WithdrawalId) {
        val walletAddress = varchar("wallet_address", 10485760)
        val walletGuid = reference("wallet_guid", WalletTable).index().nullable()
    }

    object V15_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val ownerAddress = varchar("owner_address", 10485760)
        val walletGuid = reference("wallet_guid", WalletTable).index().nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V15_WalletTable, V15_WithdrawalTable, V15_OrderTable)

            // create wallet entries for any addresses in withdrawal or order tables
            (
                V15_WithdrawalTable.selectAll().map {
                    it[V15_WithdrawalTable.walletAddress]
                } + V15_OrderTable.selectAll().map {
                    it[V15_OrderTable.ownerAddress]
                }
                ).toSet().forEach { address ->

                V15_WalletTable.insert {
                    it[V15_WalletTable.guid] = WalletId.generate(EvmAddress(address))
                    it[V15_WalletTable.address] = address
                    it[V15_WalletTable.sequencerId] = EvmAddress(address).toSequencerId().value
                    it[V15_WalletTable.createdBy] = "V15_WalletTable"
                    it[V15_WalletTable.createdAt] = Clock.System.now()
                }
            }

            // update the withdrawal and order tables
            exec(
                """
                    UPDATE withdrawal set wallet_guid=(select guid from wallet where address=wallet_address)
                """.trimIndent(),
            )
            exec(
                """
                    UPDATE "order" set wallet_guid=(select guid from wallet where address=owner_address)
                """.trimIndent(),
            )
            exec(
                """
                    ALTER TABLE withdrawal ALTER COLUMN wallet_guid SET NOT NULL
                """.trimIndent(),
            )
            exec(
                """
                    ALTER TABLE "order" ALTER COLUMN wallet_guid SET NOT NULL
                """.trimIndent(),
            )
            exec(
                """
                    ALTER TABLE withdrawal DROP COLUMN wallet_address
                """.trimIndent(),
            )
            exec(
                """
                    ALTER TABLE "order" DROP COLUMN owner_address
                """.trimIndent(),
            )
        }
    }
}
