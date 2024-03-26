package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.TradeId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Trade(
    val id: TradeId,
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val price: BigIntegerJson,
    val executionTime: Instant,
    val fee: BigIntegerJson,
    val feeSymbol: Symbol,
)

@Serializable
@SerialName("Trades")
data class WsTrades(
    val trades: List<Trade>,
) : Publishable()

data class TradesApiResponse(
    val trades: List<Trade>,
)
