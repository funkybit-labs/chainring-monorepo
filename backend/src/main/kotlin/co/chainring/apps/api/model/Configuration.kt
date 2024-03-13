package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.db.Chain
import kotlinx.serialization.Serializable

@Serializable
data class ConfigurationApiResponse(
    val addresses: List<DeployedContract>,
)

@Serializable
data class DeployedContract(
    val chain: Chain,
    val name: String,
    val address: Address,
)
