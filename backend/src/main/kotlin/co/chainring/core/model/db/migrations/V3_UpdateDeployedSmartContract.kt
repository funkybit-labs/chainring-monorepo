package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ContractId
import co.chainring.core.model.db.GUIDTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V3_UpdateDeployedSmartContract : Migration() {
    private object V3_DeployedSmartContract : GUIDTable<ContractId>("deployed_smart_contract", ::ContractId) {
        val proxyAddress = varchar("proxy_address", 10485760).nullable()
        val version = integer("version").nullable()
    }

    override fun run() {
        transaction {
            exec("ALTER TABLE deployed_smart_contract RENAME COLUMN address TO implementation_address")
            SchemaUtils.createMissingTablesAndColumns(V3_DeployedSmartContract)
        }
    }
}
