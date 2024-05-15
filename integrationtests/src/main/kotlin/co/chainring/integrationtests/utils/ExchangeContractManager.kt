package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import kotlinx.coroutines.runBlocking
import org.web3j.crypto.Keys
import java.math.BigInteger

class ExchangeContractManager {
    private val blockchainClient = TestBlockchainClient(BlockchainClientConfig())

    private val symbols: Map<String, SymbolInfo>

    init {
        val config = TestApiClient.getConfiguration()
        val chain = config.chains.first()
        symbols = chain.symbols.associateBy { it.name }
        blockchainClient.setContractAddress(ContractType.Exchange, chain.contracts.first { it.name == "Exchange" }.address)
    }

    fun getFeeBalance(symbol: String): BigInteger {
        val feeAccountAddress = blockchainClient.exchangeContract.feeAccount().send().let {
            Address(Keys.toChecksumAddress(it))
        }
        val tokenAddress = symbols.getValue(symbol).contractAddress ?: Address.zero

        return runBlocking {
            blockchainClient.getExchangeBalance(feeAccountAddress, tokenAddress)
        }
    }
}
