package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Trade(
    val id: TradeId,
    val timestamp: Instant,
    val orderId: OrderId,
    val marketId: MarketId,
    val executionRole: ExecutionRole,
    val counterOrderId: OrderId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val price: BigDecimalJson,
    val feeAmount: BigIntegerJson,
    val feeSymbol: Symbol,
    val settlementStatus: SettlementStatus,
    val error: String? = null,
)

@Serializable
data class TradesApiResponse(
    val trades: List<Trade>,
)

@Serializable
data class PublicTrade(
    val timestamp: Instant,
    val marketId: MarketId,
    val amount: BigIntegerJson,
    val price: BigDecimalJson,
    // taker's order side indicates market direction
    val side: OrderSide,
)

@Serializable
data class PublicTradesApiResponse(
    val trades: List<PublicTrade>,
)
