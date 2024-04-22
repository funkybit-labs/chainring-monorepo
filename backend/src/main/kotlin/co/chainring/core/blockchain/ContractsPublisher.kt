package co.chainring.core.blockchain

import co.chainring.core.model.Address
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.SymbolEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger

class ContractsPublisher(val blockchainClient: BlockchainClient) {
    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        transaction {
            ContractType.entries.forEach { contractType ->
                val deployedContract = DeployedSmartContractEntity
                    .findLastDeployedContractByNameAndChain(contractType.name, blockchainClient.chainId)

                if (deployedContract == null) {
                    logger.info { "Deploying contract: $contractType" }
                    deployOrUpgradeWithProxy(contractType, null)
                } else if (deployedContract.deprecated) {
                    logger.info { "Upgrading contract: $contractType" }
                    deployOrUpgradeWithProxy(contractType, deployedContract.proxyAddress)
                } else {
                    blockchainClient.setContractAddress(contractType, deployedContract.proxyAddress)
                }
            }
        }
    }

    private fun deployOrUpgradeWithProxy(contractType: ContractType, existingProxyAddress: Address?) {
        blockchainClient.deployOrUpgradeWithProxy(
            when (contractType) {
                ContractType.Exchange -> BlockchainClient.DeployContractParams.Exchange(
                    nativePrecision = SymbolEntity.forChain(blockchainClient.chainId)
                        .firstOrNull { it.contractAddress == null }?.decimals?.toInt()?.toBigInteger()
                        ?: BigInteger("18"),
                )
            },
            existingProxyAddress,
        ).also {
            DeployedSmartContractEntity.create(
                name = contractType.name,
                chainId = blockchainClient.chainId,
                implementationAddress = it.implementationAddress,
                proxyAddress = it.proxyAddress,
                version = it.version,
            )
        }
    }
}
