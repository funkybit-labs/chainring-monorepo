package co.chainring.apps.api.model

import kotlinx.serialization.Serializable

@Serializable
data class GetLastPriceResponse(
    val lastPrice: BigDecimalJson,
)
