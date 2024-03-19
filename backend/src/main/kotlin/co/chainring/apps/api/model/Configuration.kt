package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.Chain
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val contracts: List<DeployedContract>,
    val erc20Tokens: List<ERC20Token>,
    val nativeTokens: List<NativeToken>,
)

@Serializable
data class DeployedContract(
    val chain: Chain,
    val name: String,
    val address: Address,
)

@Serializable
data class ERC20Token(
    val chain: Chain,
    val name: String,
    val symbol: Symbol,
    val address: Address,
    val decimals: UByte,
)

@Serializable
data class NativeToken(
    val chain: Chain,
    val name: String,
    val symbol: String,
    val decimals: UByte,
)
