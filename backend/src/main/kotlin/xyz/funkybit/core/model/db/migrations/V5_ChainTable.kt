package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType

@Suppress("ClassName")
class V5_ChainTable : Migration() {
    private object V5_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
        override val primaryKey = PrimaryKey(id)

        val name = varchar("name", 10485760)
        val nativeTokenSymbol = varchar("native_token_symbol", 10485760)
        val nativeTokenName = varchar("native_token_name", 10485760)
        val nativeTokenDecimals = ubyte("native_token_decimals")
    }

    override fun run() {
        transaction {
            exec("ALTER TYPE Chain RENAME TO chain_enum")
            SchemaUtils.createMissingTablesAndColumns(V5_ChainTable)
            exec("INSERT INTO chain(id, name, native_token_symbol, native_token_name, native_token_decimals) VALUES(1337, 'chainring-dev', 'ETH', 'Ethereum', 18)")
            exec("ALTER TABLE deployed_smart_contract ADD COLUMN chain_id BIGINT")
            exec("UPDATE deployed_smart_contract SET chain_id = 1337")
            exec("ALTER TABLE deployed_smart_contract ALTER COLUMN chain_id SET NOT NULL")
            exec("ALTER TABLE deployed_smart_contract DROP COLUMN chain")

            exec("ALTER TABLE erc20_token ADD COLUMN chain_id BIGINT")
            exec("UPDATE erc20_token SET chain_id = 1337")
            exec("ALTER TABLE erc20_token ALTER COLUMN chain_id SET NOT NULL")
            exec("ALTER TABLE erc20_token DROP COLUMN chain")

            exec("DROP TYPE chain_enum")
            exec("UPDATE erc20_token SET guid = CONCAT('erc20_', chain_id, '_', address)")
        }
    }
}
