package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.Chain
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val contracts: List<DeployedContract>,
    val erc20Tokens: List<ERC20Token>,
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
)
