package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.TradeId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Trade(
    val id: TradeId,
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigDecimalJson,
    val price: BigDecimalJson,
    val executionTime: Instant,
    val fee: BigDecimalJson,
    val feeSymbol: Symbol,
)

data class TradesApiResponse(
    val trades: List<Trade>,
)
