package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigIntegerJson

@Serializable
data class WithdrawalFee(
    val asset: Symbol,
    val fee: BigIntegerJson,
)
