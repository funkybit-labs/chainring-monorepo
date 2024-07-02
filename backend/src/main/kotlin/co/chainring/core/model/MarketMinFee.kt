package co.chainring.core.model

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.core.model.db.MarketId
import kotlinx.serialization.Serializable

@Serializable
data class MarketMinFee(
    val marketId: MarketId,
    val minFee: BigDecimalJson,
)
