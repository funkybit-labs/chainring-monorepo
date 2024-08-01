package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.SymbolId

@Suppress("ClassName")
class V9_SymbolTable : Migration() {
    private object V9_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)
    }

    private object V9_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val name = varchar("name", 10485760)
        val chainId = reference("chain_id", V9_ChainTable)
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

            exec("DROP TABLE erc20_token")
            exec("ALTER TABLE chain DROP COLUMN native_token_symbol")
            exec("ALTER TABLE chain DROP COLUMN native_token_name")
            exec("ALTER TABLE chain DROP COLUMN native_token_decimals")

            // Deleting the data here since we want all seed data to be created by
            // a seed script and not via migrations
            exec(
                """
                DELETE FROM trade;
                DELETE FROM order_execution;
                DELETE FROM "order";
                DELETE FROM market;
                DELETE FROM chain;
                """,
            )

            exec(
                """
                ALTER TABLE market ADD COLUMN base_symbol_guid CHARACTER VARYING(10485760) NOT NULL;
                ALTER TABLE market ADD COLUMN quote_symbol_guid CHARACTER VARYING(10485760) NOT NULL;
                ALTER TABLE market DROP COLUMN base_symbol;
                ALTER TABLE market DROP COLUMN quote_symbol;
                
                ALTER TABLE market ADD CONSTRAINT market_base_symbol_guid__guid FOREIGN KEY (base_symbol_guid) REFERENCES symbol (guid);
                ALTER TABLE market ADD CONSTRAINT market_quote_symbol_guid__guid FOREIGN KEY (quote_symbol_guid) REFERENCES symbol (guid);
                """,
            )
        }
    }
}
