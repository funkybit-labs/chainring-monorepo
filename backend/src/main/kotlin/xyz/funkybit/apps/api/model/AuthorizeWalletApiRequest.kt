package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.db.ChainId

@Serializable
data class AuthorizeWalletApiRequest(
    val authorizedAddress: Address,

    val chainId: ChainId,
    val address: Address,
    val timestamp: String,
    val signature: String,
)
