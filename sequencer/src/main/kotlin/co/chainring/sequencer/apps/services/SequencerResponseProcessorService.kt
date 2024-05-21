package co.chainring.sequencer.apps.services

import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.FeeRate
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceChange
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.BroadcasterNotification
import co.chainring.core.model.db.CreateOrderAssignment
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OHLCEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.UpdateOrderAssignment
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.model.toEvmSignature
import co.chainring.core.sequencer.depositId
import co.chainring.core.sequencer.orderId
import co.chainring.core.sequencer.sequencerOrderId
import co.chainring.core.sequencer.sequencerWalletId
import co.chainring.core.sequencer.withdrawalId
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.newQuantityOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours

object SequencerResponseProcessorService {
    private val logger = KotlinLogging.logger {}

    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private val marketMap = mutableMapOf<MarketId, MarketEntity>()

    fun processResponse(response: SequencerResponse, request: SequencerRequest) {
        when (request.type) {
            SequencerRequest.Type.ApplyBalanceBatch -> {
                request.balanceBatch!!.withdrawalsList.forEach { withdrawal ->
                    WithdrawalEntity.findById(withdrawal.externalGuid.withdrawalId())?.let { withdrawalEntity ->
                        if (response.balancesChangedList.firstOrNull { it.wallet == withdrawal.wallet } == null) {
                            withdrawalEntity.update(WithdrawalStatus.Failed, error(response))
                        } else {
                            handleSequencerResponse(request, response, withdrawalEntity.wallet, listOf())
                            queueExchangeTransactions(listOf(withdrawalEntity.toEip712Transaction()))
                        }
                    }
                }

                request.balanceBatch!!.depositsList.forEach { deposit ->
                    if (response.balancesChangedList.firstOrNull { it.wallet == deposit.wallet } == null) {
                        DepositEntity.findById(deposit.externalGuid.depositId())
                            ?.update(DepositStatus.Failed, error(response))
                    } else {
                        DepositEntity.findById(deposit.externalGuid.depositId())?.let {
                            handleSequencerResponse(request, response, it.wallet, listOf())
                        }
                    }
                }

                if (request.balanceBatch.failedWithdrawalsList.isNotEmpty() ||
                    request.balanceBatch.failedSettlementsList.isNotEmpty()
                ) {
                    handleSequencerResponse(request, response, null, listOf())
                }
            }

            SequencerRequest.Type.ApplyOrderBatch -> {
                if (response.error == SequencerError.None) {
                    WalletEntity.getBySequencerId(request.orderBatch.wallet.sequencerWalletId())?.let { wallet ->
                        handleOrderBatchUpdates(request.orderBatch, wallet, response)
                        handleSequencerResponse(
                            request,
                            response,
                            wallet,
                            request.orderBatch.ordersToChangeList.map { it.guid },
                            cancelAll = request.orderBatch.cancelAll,
                        )
                    }
                }
            }

            SequencerRequest.Type.SetFeeRates -> {
                if (response.error == SequencerError.None) {
                    FeeRates(
                        maker = FeeRate(response.feeRatesSet.maker),
                        taker = FeeRate(response.feeRatesSet.taker),
                    ).persist()
                }
            }

            else -> {}
        }
    }

    private fun error(response: SequencerResponse) =
        if (response.error != SequencerError.None) response.error.name else "Rejected by sequencer"

    private fun handleOrderBatchUpdates(orderBatch: OrderBatch, wallet: WalletEntity, response: SequencerResponse) {
        if (orderBatch.ordersToAddList.isNotEmpty() || orderBatch.ordersToChangeList.isNotEmpty()) {
            val createAssignments = orderBatch.ordersToAddList.map {
                CreateOrderAssignment(
                    it.externalGuid.orderId(),
                    it.nonce.toBigInteger(),
                    toOrderType(it.type),
                    toOrderSide(it.type),
                    it.amount.toBigInteger(),
                    it.price.toBigDecimal(),
                    it.signature.toEvmSignature(),
                    it.guid.sequencerOrderId(),
                )
            }
            val ordersChangeRejected = response.ordersChangeRejectedList.map { it.guid }.toSet()

            val updateAssignments = orderBatch.ordersToChangeList.mapNotNull {
                if (!ordersChangeRejected.contains(it.guid)) {
                    UpdateOrderAssignment(
                        it.externalGuid.orderId(),
                        it.amount.toBigInteger(),
                        it.price.toBigDecimal(),
                        it.nonce.toBigInteger(),
                        it.signature.toEvmSignature(),
                    )
                } else {
                    null
                }
            }
            val market = getMarket(MarketId(orderBatch.marketId))

            OrderEntity.batchUpdate(market, wallet, createAssignments, updateAssignments)

            val createdOrders = OrderEntity.listOrders(createAssignments.map { it.orderId }).map { it.toOrderResponse() }
            val limitOrdersCreated = createdOrders.count { it is co.chainring.apps.api.model.Order.Limit } > 0

            publishBroadcasterNotifications(
                createdOrders.map { BroadcasterNotification(OrderCreated(it), wallet.address) } +
                    if (limitOrdersCreated) listOf(BroadcasterNotification.limits(wallet, market)) else emptyList(),
            )
        }
    }

    private fun handleSequencerResponse(request: SequencerRequest, response: SequencerResponse, walletEntity: WalletEntity?, ordersBeingUpdated: List<Long> = listOf(), cancelAll: Boolean = false) {
        val timestamp = Clock.System.now()

        val broadcasterNotifications = mutableListOf<BroadcasterNotification>()
        val limitsChanged = mutableSetOf<Pair<WalletEntity, MarketEntity>>()

        val orderIdsInRequest: List<Long> = (request.orderBatch.ordersToAddList + request.orderBatch.ordersToChangeList).map { it.guid }

        // handle trades
        val tradesWithTakerOrder: List<Pair<TradeEntity, OrderEntity>> = response.tradesCreatedList.mapNotNull { trade ->
            logger.debug { "Trade Created: buyOrderGuid=${trade.buyOrderGuid}, sellOrderGuid=${trade.sellOrderGuid}, amount=${trade.amount.toBigInteger()} price=${trade.price.toBigDecimal()}, buyerFee=${trade.buyerFee.toBigInteger()}, sellerFee=${trade.sellerFee.toBigInteger()}" }
            val buyOrder = OrderEntity.findBySequencerOrderId(trade.buyOrderGuid)
            val sellOrder = OrderEntity.findBySequencerOrderId(trade.sellOrderGuid)

            if (buyOrder != null && sellOrder != null) {
                val tradeEntity = TradeEntity.create(
                    timestamp = timestamp,
                    market = buyOrder.market,
                    amount = trade.amount.toBigInteger(),
                    price = trade.price.toBigDecimal(),
                )

                // create executions for both
                listOf(buyOrder, sellOrder).forEach { order ->
                    val execution = OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = order,
                        tradeEntity = tradeEntity,
                        role = if (orderIdsInRequest.contains(order.sequencerOrderId?.value)) {
                            ExecutionRole.Taker
                        } else {
                            ExecutionRole.Maker
                        },
                        feeAmount = if (order == buyOrder) {
                            trade.buyerFee.toBigInteger()
                        } else {
                            trade.sellerFee.toBigInteger()
                        },
                        feeSymbol = Symbol(order.market.quoteSymbol.name),
                    )

                    execution.refresh(flush = true)
                    logger.debug { "Sending TradeCreated for order ${order.guid}" }
                    broadcasterNotifications.add(
                        BroadcasterNotification(
                            TradeCreated(execution.toTradeResponse()),
                            recipient = order.wallet.address,
                        ),
                    )
                }

                // build the transaction to settle
                tradeEntity to if (buyOrder.type == OrderType.Market) buyOrder else sellOrder
            } else {
                null
            }
        }

        // update all orders that have changed
        response.ordersChangedList.forEach { orderChanged ->
            if (ordersBeingUpdated.contains(orderChanged.guid) || orderChanged.disposition != OrderDisposition.Accepted) {
                logger.debug { "order updated for ${orderChanged.guid}, disposition ${orderChanged.disposition}" }
                OrderEntity.findBySequencerOrderId(orderChanged.guid)?.let { orderToUpdate ->
                    orderToUpdate.updateStatus(OrderStatus.fromOrderDisposition(orderChanged.disposition))
                    orderChanged.newQuantityOrNull?.also { newQuantity ->
                        orderToUpdate.amount = newQuantity.toBigInteger()
                    }
                    if (!cancelAll) {
                        broadcasterNotifications.add(
                            BroadcasterNotification(
                                OrderUpdated(orderToUpdate.toOrderResponse()),
                                recipient = orderToUpdate.wallet.address,
                            ),
                        )
                    }
                    limitsChanged.add(Pair(orderToUpdate.wallet, orderToUpdate.market))
                }
            }
        }
        if (cancelAll && walletEntity != null) {
            broadcasterNotifications.add(
                BroadcasterNotification(
                    Orders(
                        OrderEntity
                            .listForWallet(walletEntity)
                            .map(OrderEntity::toOrderResponse),
                    ),
                    recipient = walletEntity.address,
                ),
            )
        }

        val markets = MarketEntity.all().toList()

        // update balance changes
        if (response.balancesChangedList.isNotEmpty()) {
            val walletMap = WalletEntity.getBySequencerIds(
                response.balancesChangedList.map { SequencerWalletId(it.wallet) }.toSet(),
            ).associateBy { it.sequencerId.value }

            BalanceEntity.updateBalances(
                response.balancesChangedList.mapNotNull { change ->
                    walletMap[change.wallet]?.let {
                        BalanceChange.Delta(
                            walletId = it.guid.value,
                            symbolId = getSymbol(change.asset).guid.value,
                            amount = change.delta.toBigInteger(),
                        )
                    }
                },
                BalanceType.Available,
            )
            walletMap.values.forEach {
                broadcasterNotifications.add(BroadcasterNotification.walletBalances(it))
            }

            response.balancesChangedList.forEach { change ->
                walletMap[change.wallet]?.also { wallet ->
                    val symbol = getSymbol(change.asset)
                    val symbolMarkets = markets.filter { it.baseSymbol.guid == symbol.guid || it.quoteSymbol.guid == symbol.guid }
                    symbolMarkets.forEach { market ->
                        limitsChanged.add(Pair(wallet, market))
                    }
                }
            }
        }

        // queue any blockchain txs for processing
        queueExchangeTransactions(tradesWithTakerOrder.map { it.first.toEip712Transaction() })

        val orderBookNotifications = BroadcasterNotification.orderBooksForMarkets(
            OrderEntity
                .getOrdersMarkets(response.ordersChangedList.map { it.guid })
                .sortedBy { it.guid },
        )

        val ohlcNotifications = tradesWithTakerOrder
            .fold(mutableMapOf<OrderId, MutableList<TradeEntity>>()) { acc, pair ->
                val trade = pair.first
                val orderId = pair.second.id.value
                acc[orderId] = acc.getOrDefault(orderId, mutableListOf()).also { it.add(trade) }
                acc
            }
            .map { (_, trades) ->
                val market = trades.first().market
                val marketPriceScale = market.tickSize.stripTrailingZeros().scale() + 1
                val sumOfAmounts = trades.sumOf { it.amount }
                val sumOfPricesByAmount = trades.sumOf { it.price * it.amount.toBigDecimal() }
                val weightedPrice = (sumOfPricesByAmount / sumOfAmounts.toBigDecimal()).setScale(marketPriceScale, RoundingMode.HALF_UP)

                val h24ClosePrice = OHLCEntity.findSingleByClosestStartTime(market.guid.value, OHLCDuration.P1M, OHLCDuration.P1M.durationStart(Clock.System.now() - 24.hours))?.close

                OHLCEntity.updateWith(market.guid.value, trades.first().timestamp, weightedPrice, sumOfAmounts)
                    .map {
                        BroadcasterNotification.pricesForMarketPeriods(
                            marketId = market.guid.value,
                            duration = it.duration,
                            ohlc = listOf(it),
                            full = false,
                            dailyChange = h24ClosePrice?.let { (weightedPrice.toDouble() - it.toDouble()) / it.toDouble() } ?: 0.0,
                        )
                    }
            }.flatten()

        val limitsNotifications = limitsChanged
            .groupBy(
                keySelector = { (wallet, _) -> wallet },
                valueTransform = { (_, market) -> market },
            )
            .toList()
            .sortedBy { (wallet, _) -> wallet.address.value }
            .flatMap { (wallet, markets) ->
                markets.sortedBy { it.guid }.map { market ->
                    BroadcasterNotification.limits(wallet, market)
                }
            }

        publishBroadcasterNotifications(broadcasterNotifications + orderBookNotifications + ohlcNotifications + limitsNotifications)
    }

    private fun queueExchangeTransactions(txs: List<EIP712Transaction>) {
        ExchangeTransactionEntity.createList(txs)
    }

    private fun getSymbol(asset: String): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            SymbolEntity.forName(asset)
        }
    }

    private fun getMarket(marketId: MarketId): MarketEntity {
        return marketMap.getOrPut(marketId) {
            MarketEntity[marketId]
        }
    }

    private fun toOrderType(orderType: Order.Type): OrderType {
        return when (orderType) {
            Order.Type.LimitBuy, Order.Type.LimitSell -> OrderType.Limit
            Order.Type.MarketBuy, Order.Type.MarketSell -> OrderType.Market
            else -> OrderType.Market
        }
    }

    private fun toOrderSide(orderType: Order.Type): OrderSide {
        return when (orderType) {
            Order.Type.LimitBuy, Order.Type.MarketBuy -> OrderSide.Buy
            Order.Type.LimitSell, Order.Type.MarketSell -> OrderSide.Sell
            else -> OrderSide.Sell
        }
    }
}
