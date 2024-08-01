package xyz.funkybit.apps.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.model.db.TradeId

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
