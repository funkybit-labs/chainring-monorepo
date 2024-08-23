package xyz.funkybit.testfixtures

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import xyz.funkybit.core.model.SequencerOrderId
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderBookSnapshot
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.OrderType
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.testfixtures.DbTestHelpers.createOrder
import java.math.BigDecimal
import java.math.BigInteger

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
        val responseSequence: Long,
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
            val tradesWithTakerOrders = if (tradesInDb.isNotEmpty()) {
                val lastRespSeq = tradesInDb.maxBy { it.responseSequence }.responseSequence
                tradesInDb.sortedBy { it.responseSequence }.map { trade ->
                    val tradeMarket = MarketEntity[trade.market]

                    val tradeEntity = TradeEntity.create(
                        now,
                        tradeMarket,
                        amount = trade.amount.toFundamentalUnits(tradeMarket.baseSymbol.decimals),
                        price = trade.price,
                        tradeHash = generateHexString(32),
                        trade.responseSequence,
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

                    val buyOrder = OrderEntity[trade.buyOrder]
                    val sellOrder = OrderEntity[trade.sellOrder]

                    tradeEntity to if (buyOrder.type == OrderType.Market) buyOrder else sellOrder
                }.filter { it.first.responseSequence == lastRespSeq }
            } else {
                emptyList()
            }

            TransactionManager.current().commit()

            (ordersInDb.map { it.market } + tradesInDb.map { it.market }).distinct().forEach { marketId ->
                val market = MarketEntity[marketId]
                OrderBookSnapshot
                    .calculate(market, tradesWithTakerOrders, prevSnapshot = null)
                    .save(market)
            }

            TransactionManager.current().commit()

            assertEquals(expected, getOrderBook(marketId))
        }
    }
}
