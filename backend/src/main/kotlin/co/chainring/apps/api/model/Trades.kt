package co.chainring.apps.api.model

import co.chainring.core.model.Instrument
import co.chainring.core.model.OrderId
import co.chainring.core.model.Symbol
import co.chainring.core.model.TradeId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Trade(
    val id: TradeId,
    val orderId: OrderId,
    val instrument: Instrument,
    val side: Order.Side,
    val amount: BigDecimalJson,
    val price: BigDecimalJson,
    val executionTime: Instant,
    val fee: BigDecimalJson,
    val feeSymbol: Symbol,
)

data class TradesApiResponse(
    val trades: List<Trade>,
)
