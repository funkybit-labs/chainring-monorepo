package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceChange
import co.chainring.sequencer.proto.marketCheckpoint
import co.chainring.sequencer.proto.orderChanged
import co.chainring.sequencer.proto.tradeCreated
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow

data class Market(
    val id: MarketId,
    val tickSize: BigDecimal,
    val marketPrice: BigDecimal,
    val maxLevels: Int,
    val maxOrdersPerLevel: Int,
    val baseDecimals: Int,
    val quoteDecimals: Int,
    val orderBook: OrderBook = OrderBook(
        maxLevels,
        maxOrdersPerLevel,
        tickSize,
        marketPrice,
        baseDecimals,
        quoteDecimals,
    ),
) {
    data class AddOrdersResult(
        val ordersChanged: List<OrderChanged>,
        val createdTrades: List<TradeCreated>,
        val balanceChanges: List<BalanceChange>,
    )

    fun applyOrderBatch(orderBatch: OrderBatch): AddOrdersResult {
        val ordersChanged = mutableListOf<OrderChanged>()
        val createdTrades = mutableListOf<TradeCreated>()
        val balanceChanges = mutableMapOf<Pair<WalletAddress, Asset>, BigInteger>()
        orderBatch.ordersToCancelList.forEach { orderGuid ->
            if (orderBook.removeOrder(orderGuid.toOrderGuid())) {
                ordersChanged.add(
                    orderChanged {
                        this.guid = orderGuid
                        this.disposition = OrderDisposition.Canceled
                    },
                )
            }
        }
        orderBatch.ordersToChangeList.forEach { orderChange ->
            orderBook.changeOrder(orderChange)?.let { orderDisposition ->
                ordersChanged.add(
                    orderChanged {
                        this.guid = orderChange.guid
                        this.disposition = orderDisposition
                    },
                )
            }
        }
        orderBatch.ordersToAddList.forEach { order ->
            val orderResult = orderBook.addOrder(order)
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = orderResult.disposition
                },
            )
            orderResult.executions.forEach { execution ->
                createdTrades.add(
                    tradeCreated {
                        if (order.type == Order.Type.MarketBuy) {
                            buyGuid = order.guid
                            sellGuid = execution.counterOrder.guid.value
                        } else {
                            buyGuid = execution.counterOrder.guid.value
                            sellGuid = order.guid
                        }
                        amount = execution.amount.toIntegerValue()
                        price = execution.price.toDecimalValue()
                    },
                )
                ordersChanged.add(
                    orderChanged {
                        this.guid = execution.counterOrder.guid.value
                        this.disposition = if (execution.counterOrderExhausted) OrderDisposition.Filled else OrderDisposition.PartiallyFilled
                    },
                )
                val wallet = order.wallet.toWalletAddress()
                val notional = (
                    execution.amount.toBigDecimal() * execution.price * 10.0.pow((quoteDecimals - baseDecimals).toDouble())
                        .toBigDecimal()
                    ).toBigInteger()
                val base = id.baseAsset()
                val quote = id.quoteAsset()
                val(buyer, seller) = if (order.type == Order.Type.MarketBuy) wallet to execution.counterOrder.wallet else execution.counterOrder.wallet to wallet
                balanceChanges.merge(Pair(buyer, quote), -notional, ::sumBigIntegers)
                balanceChanges.merge(Pair(seller, base), -execution.amount, ::sumBigIntegers)
                balanceChanges.merge(Pair(buyer, base), execution.amount, ::sumBigIntegers)
                balanceChanges.merge(Pair(seller, quote), notional, ::sumBigIntegers)
            }
        }
        return AddOrdersResult(
            ordersChanged,
            createdTrades,
            balanceChanges.mapNotNull { (k, delta) ->
                if (delta != BigInteger.ZERO) {
                    val (wallet, asset) = k
                    balanceChange {
                        this.wallet = wallet.value
                        this.asset = asset.value
                        this.delta = delta.toIntegerValue()
                    }
                } else {
                    null
                }
            },
        )
    }

    fun toCheckpoint(): MarketCheckpoint {
        return marketCheckpoint {
            this.id = this@Market.id.value
            this.orderBook = this@Market.orderBook.toCheckpoint()
        }
    }

    companion object {
        fun fromCheckpoint(checkpoint: MarketCheckpoint): Market {
            val orderBook = OrderBook.fromCheckpoint(checkpoint.orderBook)

            return Market(
                MarketId(checkpoint.id),
                tickSize = orderBook.tickSize,
                marketPrice = orderBook.marketPrice,
                maxLevels = orderBook.maxLevels,
                maxOrdersPerLevel = orderBook.maxOrdersPerLevel,
                baseDecimals = orderBook.baseDecimals,
                quoteDecimals = orderBook.quoteDecimals,
                orderBook = orderBook,
            )
        }
    }
}
