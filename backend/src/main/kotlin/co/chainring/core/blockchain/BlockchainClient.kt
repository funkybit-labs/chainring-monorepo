package co.chainring.core.blockchain

import co.chainring.contracts.generated.Chainring
import co.chainring.core.model.Address
import co.chainring.core.model.db.Chain
import co.chainring.core.model.db.DeployedSmartContractEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.gas.DefaultGasProvider
import kotlin.reflect.KClass

data class BlockchainClientConfig(
    val url: String = System.getenv("EVM_NETWORK_URL") ?: "http://localhost:8545",
    val privateKeyHex: String =
        System.getenv(
            "EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY",
        ) ?: "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    val gasProvider: DefaultGasProvider = DefaultGasProvider(),
    val contracts: Map<String, KClass<out Contract>> =
        mapOf(
            "chainring" to Chainring::class,
        ),
)

class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {
    private val web3j = Web3j.build(HttpService(config.url))
    private val credentials = Credentials.create(config.privateKeyHex)

    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        logger.info { "Updating deprecated contracts" }

        transaction {
            val deployedContracts = DeployedSmartContractEntity.validContracts().map { it.name }

            config.contracts.forEach {
                if (deployedContracts.contains(it.key)) {
                    logger.info { "Skipping contract: ${it.key}" }
                } else {
                    logger.info { "Deploying contract: ${it.key}" }
                    val address = deployContract(it.value)
                    DeployedSmartContractEntity.create(name = it.key, chain = Chain.Ethereum, address = address)
                    logger.info { "Deployed ${it.key} at ${address.value}" }
                }
            }
        }

        logger.info { "Contracts update finished successfully" }
    }

    private fun deployContract(contract: KClass<out Contract>): Address {
        val deployedContract =
            Contract.deployRemoteCall(
                contract.java,
                web3j,
                credentials,
                config.gasProvider,
                Chainring.BINARY,
                "",
            ).send()
        return Address(deployedContract.contractAddress)
    }
}
