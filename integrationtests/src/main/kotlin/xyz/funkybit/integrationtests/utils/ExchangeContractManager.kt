package xyz.funkybit.integrationtests.utils

import kotlinx.coroutines.runBlocking
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.DefaultBlockParam
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.utils.fromFundamentalUnits
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
            val chain = config.evmChains.first { it.id == chainId }
            blockchainClient.setContractAddress(
                ContractType.Exchange,
                chain.contracts.first { it.name == "Exchange" }.address,
            )
        }
        symbols = config.evmChains.map { chain ->
            chain.symbols.associateBy { it.name }
        }.flatMap { map -> map.entries }.associate(Map.Entry<String, SymbolInfo>::toPair)
        symbolByChainId = config.evmChains.map { chain ->
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
        val tokenAddress = symbols.getValue(symbol).contractAddress ?: EvmAddress.zero

        return runBlocking {
            blockchainClient.getExchangeBalance(feeAccountAddress, tokenAddress, DefaultBlockParam.Latest)
        }
    }
}
