package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.evm.EvmClient
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.DeployedSmartContractEntity

class EvmContractsPublisher(val evmClient: EvmClient) {
    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        transaction {
            ContractType.entries.forEach { contractType ->
                val deployedContract = DeployedSmartContractEntity
                    .findLastDeployedContractByNameAndChain(contractType.name, evmClient.chainId)

                if (deployedContract == null) {
                    logger.info { "Deploying contract: $contractType" }
                    deployOrUpgradeWithProxy(contractType, null)
                } else if (deployedContract.deprecated) {
                    deployedContract.proxyAddress.let {
                        if (it is EvmAddress) {
                            logger.info { "Upgrading contract: $contractType" }
                            deployOrUpgradeWithProxy(contractType, it)
                        }
                    }
                } else {
                    deployedContract.proxyAddress.let {
                        if (it is EvmAddress) {
                            evmClient.setContractAddress(contractType, it)
                        }
                    }
                }
            }
        }
    }

    private fun deployOrUpgradeWithProxy(contractType: ContractType, existingProxyAddress: EvmAddress?) {
        evmClient.deployOrUpgradeWithProxy(
            contractType,
            existingProxyAddress,
        ).also {
            DeployedSmartContractEntity.create(
                name = contractType.name,
                chainId = evmClient.chainId,
                implementationAddress = it.implementationAddress,
                proxyAddress = it.proxyAddress,
                version = it.version,
            )
        }
    }
}
