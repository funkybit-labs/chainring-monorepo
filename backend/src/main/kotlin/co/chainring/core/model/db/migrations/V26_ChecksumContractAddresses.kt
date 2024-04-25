package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.ContractId
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.SymbolId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.web3j.crypto.Keys

@Suppress("ClassName")
class V26_ChecksumContractAddresses : Migration() {

    private val logger = KotlinLogging.logger {}

    private object V26_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
        val contractAddress = varchar(
            "contract_address",
            10485760,
        ).nullable()
    }

    private object V26_DeployedSmartContract : GUIDTable<ContractId>("deployed_smart_contract", ::ContractId) {
        val proxyAddress = varchar("proxy_address", 10485760).nullable()
        val implementationAddress = varchar("implementation_address", 10485760)
        val deprecated = bool("deprecated")
    }

    override fun run() {
        transaction {
            // checksum wallet address
            V26_SymbolTable.selectAll().where(V26_SymbolTable.contractAddress.isNotNull()).forEach { resultRow ->
                val guid = resultRow[V26_SymbolTable.guid]
                val checksumAddress = Keys.toChecksumAddress(resultRow[V26_SymbolTable.contractAddress])
                logger.debug { "Converting erc20 contract address ${resultRow[V26_SymbolTable.contractAddress]} to $checksumAddress" }
                V26_SymbolTable.update({ V26_SymbolTable.guid.eq(guid) }) {
                    it[this.contractAddress] = checksumAddress
                }
            }

            V26_DeployedSmartContract.selectAll().where(V26_DeployedSmartContract.deprecated.eq(false)).forEach { resultRow ->
                val guid = resultRow[V26_DeployedSmartContract.guid]
                val checksumProxyAddress = Keys.toChecksumAddress(resultRow[V26_DeployedSmartContract.proxyAddress])
                val checksumImplementationAddress = Keys.toChecksumAddress(resultRow[V26_DeployedSmartContract.implementationAddress])
                logger.debug { "Converting proxy address  ${resultRow[V26_DeployedSmartContract.proxyAddress]} to $checksumProxyAddress" }
                logger.debug { "Converting implementation address ${resultRow[V26_DeployedSmartContract.implementationAddress]} to $checksumImplementationAddress" }
                V26_DeployedSmartContract.update({ V26_DeployedSmartContract.guid.eq(guid) }) {
                    it[this.proxyAddress] = checksumProxyAddress
                    it[this.implementationAddress] = checksumImplementationAddress
                }
            }
        }
    }
}
