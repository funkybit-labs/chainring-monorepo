package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.core.model.db.MarketId

@Serializable
data class MarketMinFee(
    val marketId: MarketId,
    val minFee: BigDecimalJson,
)
