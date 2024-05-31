package co.chainring.apps.api.model

import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import kotlinx.serialization.Serializable

@Serializable
data class FaucetApiRequest(
    val chainId: ChainId,
    val address: Address,
)

@Serializable
data class FaucetApiResponse(
    val chainId: ChainId,
    val txHash: TxHash,
    val amount: BigIntegerJson,
)
