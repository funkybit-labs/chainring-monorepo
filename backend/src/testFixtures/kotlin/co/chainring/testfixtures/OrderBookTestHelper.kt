package co.chainring.testfixtures

import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderBookSnapshot
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.generateHexString
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.testfixtures.DbTestHelpers.createOrder
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration

object OrderBookTestHelper {
    data class Order(
        val id: OrderId,
        val wallet: WalletEntity,
        val market: MarketId,
        val side: OrderSide,
        val type: OrderType,
        val status: OrderStatus,
        val price: BigDecimal?,
        val amount: BigDecimal,
    )

    data class Trade(
        val market: MarketId,
        val timeSinceHappened: Duration,
        val buyOrder: OrderId,
        val sellOrder: OrderId,
        val amount: BigDecimal,
        val price: BigDecimal,
    )

    fun <OB> verifyOrderBook(
        marketId: MarketId,
        ordersInDb: List<Order>,
        tradesInDb: List<Trade>,
        expected: OB,
        getOrderBook: (marketId: MarketId) -> OB,
    ) {
        transaction {
            ordersInDb.forEachIndexed { i, order ->
                createOrder(
                    MarketEntity[order.market],
                    order.wallet,
                    order.side, order.type, order.amount, order.price, order.status,
                    SequencerOrderId(i.toLong()),
                    order.id,
                )
            }

            TransactionManager.current().commit()

            val now = Clock.System.now()
            tradesInDb.forEach { trade ->
                val tradeMarket = MarketEntity[trade.market]

                val tradeEntity = TradeEntity.create(
                    now.minus(trade.timeSinceHappened),
                    tradeMarket,
                    amount = trade.amount.toFundamentalUnits(tradeMarket.baseSymbol.decimals),
                    price = trade.price,
                    tradeHash = generateHexString(32),
                    0L,
                )

                listOf(Pair(trade.buyOrder, trade.sellOrder), Pair(trade.sellOrder, trade.buyOrder)).forEach { (orderId, counterOrderId) ->
                    val order = OrderEntity[orderId]
                    val counterOrder = OrderEntity[counterOrderId]
                    assertTrue(
                        order.status == OrderStatus.Filled || order.status == OrderStatus.Partial,
                        "Order must be filled or partially filled",
                    )
                    OrderExecutionEntity.create(
                        timestamp = tradeEntity.timestamp,
                        orderEntity = order,
                        counterOrderEntity = counterOrder,
                        tradeEntity = tradeEntity,
                        role = if (order.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(order.market.quoteSymbol.name),
                    )
                }
            }

            TransactionManager.current().commit()

            (ordersInDb.map { it.market } + tradesInDb.map { it.market }).distinct().forEach { marketId ->
                val market = MarketEntity[marketId]
                OrderBookSnapshot
                    .calculate(market)
                    .save(market)
            }

            TransactionManager.current().commit()

            assertEquals(expected, getOrderBook(marketId))
        }
    }
}
