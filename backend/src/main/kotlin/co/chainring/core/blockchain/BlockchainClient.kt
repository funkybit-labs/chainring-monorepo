package co.chainring.core.blockchain

import co.chainring.contracts.generated.ERC1967Proxy
import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.UUPSUpgradeable
import co.chainring.core.model.Address
import co.chainring.core.model.db.Chain
import co.chainring.core.model.db.DeployedSmartContractEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger

enum class ContractType {
    Exchange,
}

data class BlockchainClientConfig(
    val url: String = System.getenv("EVM_NETWORK_URL") ?: "http://localhost:8545",
    val privateKeyHex: String =
        System.getenv(
            "EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY",
        ) ?: "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    val gasProvider: DefaultGasProvider = DefaultGasProvider(),
)

class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {
    private val web3j = Web3j.build(HttpService(config.url))
    private val credentials = Credentials.create(config.privateKeyHex)

    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        logger.info { "Updating deprecated contracts" }

        transaction {
            DeployedSmartContractEntity.validContracts().map { it.name }

            ContractType.entries.forEach {
                DeployedSmartContractEntity.findLastDeployedContractByNameAndChain(ContractType.Exchange.name, Chain.Ethereum)?.let {
                        deployedContract ->
                    if (deployedContract.deprecated) {
                        logger.info { "Upgrading contract: $it" }
                        deployOrUpgradeWithProxy(it, deployedContract.proxyAddress)
                    } else {
                        logger.info { "Skipping contract: $it" }
                    }
                } ?: run {
                    logger.info { "Deploying contract: $it" }
                    deployOrUpgradeWithProxy(it, null)
                }
            }
        }

        logger.info { "Contracts update finished successfully" }
    }

    private fun deployOrUpgradeWithProxy(
        contractType: ContractType,
        existingProxyAddress: Address?,
    ) {
        logger.debug { "Starting deployment for $contractType" }
        val (implementationContractAddress, version) =
            when (contractType) {
                ContractType.Exchange -> {
                    val implementationContract = Exchange.deploy(web3j, credentials, config.gasProvider).send()
                    Pair(
                        Address(implementationContract.contractAddress),
                        implementationContract.version.send().toInt(),
                    )
                }
            }
        val proxyAddress =
            if (existingProxyAddress != null) {
                // for now call upgradeTo here
                logger.debug { "Calling upgradeTo for $contractType" }
                UUPSUpgradeable.load(
                    existingProxyAddress.value,
                    web3j,
                    credentials,
                    config.gasProvider,
                ).upgradeToAndCall(implementationContractAddress.value, ByteArray(0), BigInteger.ZERO).send()
                existingProxyAddress
            } else {
                // deploy the proxy and call the initialize method in contract - this can only be called once
                logger.debug { "Deploying proxy for $contractType" }
                val proxyContract =
                    ERC1967Proxy.deploy(
                        web3j,
                        credentials,
                        config.gasProvider,
                        BigInteger.ZERO,
                        implementationContractAddress.value,
                        ByteArray(0),
                    ).send()
                logger.debug { "Deploying initialize for $contractType" }
                when (contractType) {
                    ContractType.Exchange -> {
                        Exchange.load(proxyContract.contractAddress, web3j, credentials, config.gasProvider).initialize().send()
                    }
                }
                Address(proxyContract.contractAddress)
            }

        logger.debug { "Creating db entry for $contractType" }
        DeployedSmartContractEntity.create(
            name = contractType.name,
            chain = Chain.Ethereum,
            implementationAddress = implementationContractAddress,
            proxyAddress = proxyAddress,
            version = version,
        )
        logger.debug { "Deployment complete for $contractType" }
    }
}
