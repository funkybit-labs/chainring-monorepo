package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable

@Serializable
data class GetLastPriceResponse(
    val lastPrice: BigDecimalJson,
)
