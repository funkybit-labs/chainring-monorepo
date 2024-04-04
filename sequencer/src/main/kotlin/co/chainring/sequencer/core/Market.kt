package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceChange
import co.chainring.sequencer.proto.orderChanged
import co.chainring.sequencer.proto.tradeCreated
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow

class Market(
    val id: MarketId,
    tickSize: BigDecimal,
    marketPrice: BigDecimal,
    maxLevels: Int,
    maxOrdersPerLevel: Int,
    val baseDecimals: Int,
    val quoteDecimals: Int,
) {
    data class AddOrdersResult(
        val ordersChanged: List<OrderChanged>,
        val createdTrades: List<TradeCreated>,
        val balanceChanges: List<BalanceChange>,
    )

    fun addOrders(ordersToAddList: List<Order>): AddOrdersResult {
        val ordersChanged = mutableListOf<OrderChanged>()
        val createdTrades = mutableListOf<TradeCreated>()
        val balanceChanges = mutableMapOf<Pair<WalletAddress, Asset>, BigInteger>()
        ordersToAddList.forEach { order ->
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
                            sellGuid = execution.counterGuid.value
                        } else {
                            buyGuid = execution.counterGuid.value
                            sellGuid = order.guid
                        }
                        amount = execution.amount.toIntegerValue()
                        price = execution.price.toDecimalValue()
                    },
                )
                ordersChanged.add(
                    orderChanged {
                        this.guid = execution.counterGuid.value
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
                val(buyer, seller) = if (order.type == Order.Type.MarketBuy) wallet to execution.counterWallet else execution.counterWallet to wallet
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

    val orderBook = OrderBook(maxLevels, maxOrdersPerLevel, tickSize, marketPrice, baseDecimals, quoteDecimals)
}
