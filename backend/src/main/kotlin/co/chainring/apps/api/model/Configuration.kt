package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainId
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
    val feeRatesInBps: FeeRatesInBps,
)

@Serializable
data class FeeRatesInBps(
    val maker: Int,
    val taker: Int,
)

@Serializable
data class Chain(
    val id: ChainId,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
)

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
)

@Serializable
data class SymbolInfo(
    val name: String,
    val description: String,
    val contractAddress: Address?,
    val decimals: UByte,
)

@Serializable
data class Market(
    val id: String,
    val baseSymbol: Symbol,
    val baseDecimals: Int,
    val quoteSymbol: Symbol,
    val quoteDecimals: Int,
    val tickSize: BigDecimalJson,
)
