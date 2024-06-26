package co.chainring.core.model

import co.chainring.apps.api.model.BigIntegerJson
import kotlinx.serialization.Serializable

@Serializable
data class WithdrawalFee(
    val asset: Symbol,
    val fee: BigIntegerJson,
)
