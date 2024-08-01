package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.db.DeployedSmartContractEntity

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
