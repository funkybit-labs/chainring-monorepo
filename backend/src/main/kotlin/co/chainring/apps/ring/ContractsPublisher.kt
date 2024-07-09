package co.chainring.apps.ring

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import co.chainring.core.model.db.DeployedSmartContractEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction

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
            contractType,
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
