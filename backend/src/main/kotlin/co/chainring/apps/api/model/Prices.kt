package co.chainring.apps.api.model

import co.chainring.core.model.db.MarketId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("Prices")
data class Prices(
    val market: MarketId,
    val ohlc: List<OHLC>,
) : Publishable()

@Serializable
data class OHLC(
    val start: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val durationMs: Long,
)
