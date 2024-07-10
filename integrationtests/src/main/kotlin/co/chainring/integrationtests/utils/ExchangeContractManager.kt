package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.blockchain.DefaultBlockParam
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.utils.fromFundamentalUnits
import kotlinx.coroutines.runBlocking
import java.math.BigInteger

class ExchangeContractManager {
    private val blockchainClients = ChainManager.blockchainConfigs.associate {
        val blockchainClient = TestBlockchainClient(it)
        blockchainClient.chainId to blockchainClient
    }

    private val symbols: Map<String, SymbolInfo>
    private val symbolByChainId: Map<String, ChainId>

    init {
        val config = TestApiClient.getConfiguration()
        blockchainClients.forEach { (chainId, blockchainClient) ->
            val chain = config.chains.first { it.id == chainId }
            blockchainClient.setContractAddress(
                ContractType.Exchange,
                chain.contracts.first { it.name == "Exchange" }.address,
            )
        }
        symbols = config.chains.map { chain ->
            chain.symbols.associateBy { it.name }
        }.flatMap { map -> map.entries }.associate(Map.Entry<String, SymbolInfo>::toPair)
        symbolByChainId = config.chains.map { chain ->
            chain.symbols.associate { it.name to chain.id }
        }.flatMap { map -> map.entries }.associate(Map.Entry<String, ChainId>::toPair)
    }

    fun getFeeBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            getFeeBalance(symbol.name).fromFundamentalUnits(symbol.decimals),
        )

    fun getFeeBalance(symbol: String): BigInteger {
        val blockchainClient = blockchainClients.getValue(symbolByChainId.getValue(symbol))
        val feeAccountAddress = blockchainClient.getFeeAccountAddress(DefaultBlockParam.Latest)
        val tokenAddress = symbols.getValue(symbol).contractAddress ?: Address.zero

        return runBlocking {
            blockchainClient.getExchangeBalance(feeAccountAddress, tokenAddress, DefaultBlockParam.Latest)
        }
    }
}
