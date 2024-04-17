package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ChainTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V18_BlockchainNonce : Migration() {

    object V18_BlockchainNonceTable : IntIdTable("blockchain_nonce") {
        val key = varchar("key", 10485760)
        val chainId = reference("chain_id", ChainTable)
        val nonce = decimal("nonce", 30, 0).nullable()
        init {
            index(true, key, chainId)
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V18_BlockchainNonceTable)
        }
    }
}
