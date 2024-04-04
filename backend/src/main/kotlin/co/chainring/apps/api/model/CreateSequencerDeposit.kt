package co.chainring.apps.api.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateSequencerDeposit(
    val symbol: String,
    val amount: BigIntegerJson,
)
