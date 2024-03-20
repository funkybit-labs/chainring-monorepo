package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainId
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
)

@Serializable
data class Chain(
    val id: ChainId,
    val contracts: List<DeployedContract>,
    val erc20Tokens: List<ERC20Token>,
    val nativeToken: NativeToken,
)

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
)

@Serializable
data class ERC20Token(
    val name: String,
    val symbol: Symbol,
    val address: Address,
    val decimals: UByte,
)

@Serializable
data class NativeToken(
    val name: String,
    val symbol: Symbol,
    val decimals: UByte,
)
