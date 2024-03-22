package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ChainTable
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.SymbolId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V9_SymbolTable : Migration() {
    private object V9_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val name = varchar("name", 10485760)
        val chainId = reference("chain_id", ChainTable)
        val contractAddress = varchar("contract_address", 10485760).nullable()
        val decimals = ubyte("decimals")
        val description = varchar("description", 10485760)
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)

        init {
            uniqueIndex(
                customIndexName = "uix_symbol_chain_id_name",
                columns = arrayOf(chainId, name),
            )
            uniqueIndex(
                customIndexName = "uix_symbol_chain_id_contract_address",
                columns = arrayOf(chainId, contractAddress),
            )
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V9_SymbolTable)

            exec(
                """
                INSERT INTO symbol(guid, name, chain_id, contract_address, decimals, description, created_at, created_by)
                VALUES('s_eth_1337', 'ETH', 1337, NULL, 18, 'Ethereum', NOW(), 'system');
                
                INSERT INTO symbol(guid, name, chain_id, contract_address, decimals, description, created_at, created_by)
                SELECT 
                    CONCAT('s_', LOWER(erc20_token.symbol), '_', erc20_token.chain_id),
                    erc20_token.symbol,
                    erc20_token.chain_id,
                    erc20_token.address,
                    erc20_token.decimals,
                    erc20_token.name,
                    NOW(), 
                    'system'
                FROM erc20_token;
                """.trimIndent(),
            )

            exec("DROP TABLE erc20_token")
            exec("ALTER TABLE chain DROP COLUMN native_token_symbol")
            exec("ALTER TABLE chain DROP COLUMN native_token_name")
            exec("ALTER TABLE chain DROP COLUMN native_token_decimals")

            exec("ALTER TABLE market ADD COLUMN ")
        }
    }
}
