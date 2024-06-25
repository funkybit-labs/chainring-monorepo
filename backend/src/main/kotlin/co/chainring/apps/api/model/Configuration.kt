package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.MarketId
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
    val feeRates: FeeRates,
)

@Serializable
data class Chain(
    val id: ChainId,
    val name: String,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
    val jsonRpcUrl: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
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
    val faucetSupported: Boolean,
)

@Serializable
data class Market(
    val id: MarketId,
    val baseSymbol: Symbol,
    val baseDecimals: Int,
    val quoteSymbol: Symbol,
    val quoteDecimals: Int,
    val tickSize: BigDecimalJson,
    val lastPrice: BigDecimalJson,
    val minAllowedBidPrice: BigDecimalJson,
    val maxAllowedOfferPrice: BigDecimalJson,
)
