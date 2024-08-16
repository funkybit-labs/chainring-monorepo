package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId

@Serializable
data class FaucetApiRequest(
    val symbol: Symbol,
    val address: EvmAddress,
)

@Serializable
data class FaucetApiResponse(
    val chainId: ChainId,
    val txHash: TxHash,
    val symbol: Symbol,
    val amount: BigIntegerJson,
)
