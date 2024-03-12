package co.chainring.core.blockchain

import co.chainring.generated.Chainring
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider

data class BlockchainClientConfig(
    val url: String = System.getenv("EVM_NETWORK_URL") ?: "http://localhost:8545",
    val privateKeyHex: String = System.getenv("EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY") ?: "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    val gasProvider: DefaultGasProvider = DefaultGasProvider(),
)

class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {

    private val web3j = Web3j.build(HttpService(config.url))
    private val credentials = Credentials.create(config.privateKeyHex)

    fun deployChainringContract(): String {
        val contract = Chainring.deploy(web3j, credentials, config.gasProvider).send()

        return contract.contractAddress
    }
}
