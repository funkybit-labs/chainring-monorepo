package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
)

@Serializable
data class Chain(
    val id: ChainId,
    val contracts: List<DeployedContract>,
    val symbols: List<Symbol>,
)

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
)

@Serializable
data class Symbol(
    val name: String,
    val description: String,
    val contractAddress: Address?,
    val decimals: UByte,
)

@Serializable
data class Market(
    val id: String,
    val baseSymbol: Symbol,
    val quoteSymbol: Symbol,
)
