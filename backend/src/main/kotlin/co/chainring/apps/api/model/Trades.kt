package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TradeId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Trade(
    val id: TradeId,
    val timestamp: Instant,
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val price: BigIntegerJson,
    val feeAmount: BigIntegerJson,
    val feeSymbol: Symbol,
)

@Serializable
@SerialName("Trades")
data class WsTrades(
    val trades: List<Trade>,
) : Publishable()

@Serializable
data class TradesApiResponse(
    val trades: List<Trade>,
)

@Serializable
data class PublicTrade(
    val timestamp: Instant,
    val marketId: MarketId,
    val amount: BigIntegerJson,
    val price: BigIntegerJson,
    // taker's order side indicates market direction
    val side: OrderSide,
)

@Serializable
data class PublicTradesApiResponse(
    val trades: List<PublicTrade>,
)
