package xyz.funkybit.sequencer.apps.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.funkybit.apps.api.model.MarketLimits
import xyz.funkybit.apps.api.model.websocket.MarketTradesCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderCreated
import xyz.funkybit.apps.api.model.websocket.MyOrderUpdated
import xyz.funkybit.apps.api.model.websocket.MyTradesCreated
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.apps.api.model.websocket.OrderBookDiff
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.FeeRate
import xyz.funkybit.core.model.SequencerWalletId
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.BalanceChange
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.BroadcasterNotification
import xyz.funkybit.core.model.db.CreateOrderAssignment
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.KeyValueStore
import xyz.funkybit.core.model.db.LimitEntity
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCDuration
import xyz.funkybit.core.model.db.OHLCEntity
import xyz.funkybit.core.model.db.OrderBookSnapshot
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.OrderType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.publishBroadcasterNotifications
import xyz.funkybit.core.model.db.toOrderResponse
import xyz.funkybit.core.model.toEvmSignature
import xyz.funkybit.core.sequencer.clientOrderId
import xyz.funkybit.core.sequencer.depositId
import xyz.funkybit.core.sequencer.orderId
import xyz.funkybit.core.sequencer.sequencerOrderId
import xyz.funkybit.core.sequencer.sequencerWalletId
import xyz.funkybit.core.sequencer.withdrawalId
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.proto.BackToBackOrder
import xyz.funkybit.sequencer.proto.LimitsUpdate
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderBatch
import xyz.funkybit.sequencer.proto.OrderChanged
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerError
import xyz.funkybit.sequencer.proto.SequencerRequest
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.TradeCreated
import xyz.funkybit.sequencer.proto.bidOfferStateOrNull
import xyz.funkybit.sequencer.proto.newQuantityOrNull
import java.math.BigDecimal
import java.math.BigInteger
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
                    val withdrawalEntity = WithdrawalEntity.findById(withdrawal.externalGuid.withdrawalId())!!
                    val balanceChange = response.balancesChangedList.firstOrNull { it.wallet == withdrawal.wallet }
                    if (balanceChange == null) {
                        withdrawalEntity.update(WithdrawalStatus.Failed, error(response, "Insufficient Balance"))
                    } else {
                        handleSequencerResponse(response)
                        withdrawalEntity.update(
                            WithdrawalStatus.Sequenced,
                            null,
                            actualAmount = balanceChange.delta.toBigInteger().negate(),
                            fee = response.withdrawalsCreatedList.firstOrNull { it.externalGuid.withdrawalId() == withdrawalEntity.guid.value }?.fee?.toBigInteger() ?: BigInteger.ZERO,
                            responseSequence = response.sequence,
                        )
                    }
                }

                request.balanceBatch!!.depositsList.forEach { deposit ->
                    val depositEntity = DepositEntity.findById(deposit.externalGuid.depositId())!!
                    if (response.balancesChangedList.firstOrNull { it.wallet == deposit.wallet } == null) {
                        depositEntity.markAsFailed(error(response))
                    } else {
                        handleSequencerResponse(response)
                        depositEntity.markAsComplete()
                    }
                }

                if (request.balanceBatch.failedWithdrawalsList.isNotEmpty() ||
                    request.balanceBatch.failedSettlementsList.isNotEmpty()
                ) {
                    handleSequencerResponse(response)
                }
            }

            SequencerRequest.Type.ApplyOrderBatch -> {
                response.bidOfferStateOrNull?.let {
                    logger.debug { "minBidIx=${it.minBidIx}, bestBidIx = ${it.bestBidIx}, bestOfferIx = ${it.bestOfferIx}, maxOfferIx=${it.maxOfferIx}" }
                    if (it.bestBidIx >= it.bestOfferIx) {
                        request.orderBatch.ordersToAddList.forEach {
                            logger.debug { "add - ${it.guid} ${it.externalGuid} ${it.type} ${it.amount.toBigInteger()} ${it.levelIx}" }
                        }
                        request.orderBatch.ordersToCancelList.forEach {
                            logger.debug { "cancel - ${it.guid} ${it.externalGuid}" }
                        }
                        response.ordersChangedList.forEach {
                            logger.debug { "changed - ${it.guid} ${it.disposition}" }
                        }
                    }
                }

                if (response.error == SequencerError.None) {
                    WalletEntity.getBySequencerId(request.orderBatch.wallet.sequencerWalletId())?.let { wallet ->
                        handleOrderBatchUpdates(request.orderBatch, wallet, response)
                        handleSequencerResponse(
                            response,
                            orderIdsInRequest = request.orderBatch.ordersToAddList.map { it.guid },
                        )
                    }
                }
            }

            SequencerRequest.Type.ApplyBackToBackOrder -> {
                if (response.error == SequencerError.None) {
                    WalletEntity.getBySequencerId(request.backToBackOrder.wallet.sequencerWalletId())?.let { wallet ->
                        handleBackToBackMarketOrder(request.backToBackOrder, wallet, response)
                        handleSequencerResponse(
                            response,
                            orderIdsInRequest = listOf(request.backToBackOrder.order.guid),
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

            SequencerRequest.Type.AddMarket -> {
                if (response.error == SequencerError.None) {
                    response.marketsCreatedList.forEach { marketCreated ->
                        MarketEntity[MarketId(marketCreated.marketId)].apply {
                            minFee = if (marketCreated.hasMinFee()) marketCreated.minFee.toBigInteger() else BigInteger.ZERO
                            updatedAt = Clock.System.now()
                            updatedBy = "seq:${response.sequence}"
                        }
                    }
                }
            }

            SequencerRequest.Type.SetWithdrawalFees -> {
                if (response.error == SequencerError.None) {
                    response.withdrawalFeesSetList.forEach {
                        SymbolEntity.forName(it.asset).apply {
                            withdrawalFee = it.value.toBigInteger()
                            updatedAt = Clock.System.now()
                            updatedBy = "seq:${response.sequence}"
                        }
                    }
                }
            }

            SequencerRequest.Type.SetMarketMinFees -> {
                if (response.error == SequencerError.None) {
                    response.marketMinFeesSetList.forEach { entry ->
                        MarketEntity.findById(MarketId(entry.marketId))?.apply {
                            minFee = entry.minFee.toBigInteger()
                            updatedAt = Clock.System.now()
                            updatedBy = "seq:${response.sequence}"
                        }
                    }
                }
            }

            else -> {}
        }
    }

    private fun error(response: SequencerResponse, defaultMessage: String = "Rejected by sequencer") =
        if (response.error != SequencerError.None) response.error.name else defaultMessage

    private fun handleOrderBatchUpdates(orderBatch: OrderBatch, wallet: WalletEntity, response: SequencerResponse) {
        if (orderBatch.ordersToAddList.isNotEmpty()) {
            val createAssignments = orderBatch.ordersToAddList.map {
                CreateOrderAssignment(
                    orderId = it.externalGuid.orderId(),
                    clientOrderId = it.clientOrderGuid?.takeIf { it.isNotEmpty() }?.clientOrderId(),
                    nonce = it.nonce.toBigInteger(),
                    type = toOrderType(it.type),
                    side = toOrderSide(it.type),
                    amount = it.amount.toBigInteger(),
                    levelIx = it.levelIx,
                    signature = it.signature.toEvmSignature(),
                    sequencerOrderId = it.guid.sequencerOrderId(),
                    sequencerTimeNs = response.processingTime.toBigInteger(),
                )
            }

            val market = getMarket(MarketId(orderBatch.marketId))

            OrderEntity.batchCreate(market, wallet, createAssignments)

            val createdOrders = OrderEntity.listOrdersWithExecutions(createAssignments.map { it.orderId }).map { it.toOrderResponse() }

            publishBroadcasterNotifications(
                createdOrders.map { BroadcasterNotification(MyOrderCreated(it), wallet.address) },
            )
        }
    }

    private fun handleBackToBackMarketOrder(backToBackOrder: BackToBackOrder, wallet: WalletEntity, response: SequencerResponse) {
        val createAssignment =
            CreateOrderAssignment(
                orderId = backToBackOrder.order.externalGuid.orderId(),
                clientOrderId = backToBackOrder.order.clientOrderGuid?.takeIf { it.isNotEmpty() }?.clientOrderId(),
                nonce = backToBackOrder.order.nonce.toBigInteger(),
                type = OrderType.BackToBackMarket,
                side = toOrderSide(backToBackOrder.order.type),
                amount = backToBackOrder.order.amount.toBigInteger(),
                levelIx = backToBackOrder.order.levelIx,
                signature = backToBackOrder.order.signature.toEvmSignature(),
                sequencerOrderId = backToBackOrder.order.guid.sequencerOrderId(),
                sequencerTimeNs = response.processingTime.toBigInteger(),
            )

        val market = getMarket(MarketId(backToBackOrder.marketIdsList[0]))
        val backToBackMarket = getMarket(MarketId(backToBackOrder.marketIdsList[1]))

        OrderEntity.batchCreate(market, wallet, listOf(createAssignment), backToBackMarket = backToBackMarket)

        val createdOrder = OrderEntity.listOrdersWithExecutions(listOf(createAssignment.orderId)).map { it.toOrderResponse() }.first()

        publishBroadcasterNotifications(
            listOf(BroadcasterNotification(MyOrderCreated(createdOrder), wallet.address)),
        )
    }

    private fun handleSequencerResponse(response: SequencerResponse, orderIdsInRequest: List<Long> = listOf()) {
        val timestamp = if (response.createdAt > 0) {
            Instant.fromEpochMilliseconds(response.createdAt)
        } else {
            Clock.System.now()
        }

        val broadcasterNotifications = mutableListOf<BroadcasterNotification>()

        val tradesWithTakerOrder = handleTrades(response.tradesCreatedList, response.sequence, orderIdsInRequest, timestamp, broadcasterNotifications)
        handleChangedOrders(response.ordersChangedList, timestamp, broadcasterNotifications)
        handleBalanceChanges(response.balancesChangedList, broadcasterNotifications)
        updateOrderBookSnapshot(response.ordersChangedList, tradesWithTakerOrder, broadcasterNotifications)
        updateOhlc(tradesWithTakerOrder, broadcasterNotifications)
        handleLimitsUpdates(response.limitsUpdatedList, broadcasterNotifications)

        publishBroadcasterNotifications(broadcasterNotifications)
    }

    private fun handleTrades(createdTrades: List<TradeCreated>, responseSequence: Long, orderIdsInRequest: List<Long> = listOf(), timestamp: Instant, broadcasterNotifications: MutableList<BroadcasterNotification>): List<Pair<TradeEntity, OrderEntity>> {
        val executionsCreatedByWallet = mutableMapOf<Address, MutableList<OrderExecutionEntity>>()
        val tradesWithTakerOrders = createdTrades.mapNotNull { trade ->
            logger.debug { "Trade Created: buyOrderGuid=${trade.buyOrderGuid}, sellOrderGuid=${trade.sellOrderGuid}, amount=${trade.amount.toBigInteger()} levelIx=${trade.levelIx}, buyerFee=${trade.buyerFee.toBigInteger()}, sellerFee=${trade.sellerFee.toBigInteger()}" }
            val buyOrder = OrderEntity.findBySequencerOrderId(trade.buyOrderGuid)
            val sellOrder = OrderEntity.findBySequencerOrderId(trade.sellOrderGuid)

            val tradeMarket = getMarket(MarketId(trade.marketId))

            if (buyOrder != null && sellOrder != null) {
                val tradeEntity = TradeEntity.create(
                    timestamp = timestamp,
                    market = tradeMarket,
                    amount = trade.amount.toBigInteger(),
                    price = tradeMarket.tickSize.multiply(trade.levelIx.toBigDecimal()),
                    tradeHash = ECHelper.tradeHash(trade.buyOrderGuid, trade.sellOrderGuid),
                    responseSequence = responseSequence,
                )

                // create executions for both
                listOf(Pair(buyOrder, sellOrder), Pair(sellOrder, buyOrder)).forEach { (order, counterOrder) ->
                    val execution = OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = order,
                        counterOrderEntity = counterOrder,
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
                        feeSymbol = Symbol(tradeMarket.quoteSymbol.name),
                        marketEntity = tradeMarket,
                    )

                    execution.refresh(flush = true)
                    executionsCreatedByWallet
                        .getOrPut(execution.order.wallet.address) { mutableListOf() }
                        .add(execution)
                }

                // build the transaction to settle
                tradeEntity to if (buyOrder.type == OrderType.Market) buyOrder else sellOrder
            } else {
                null
            }
        }

        executionsCreatedByWallet.forEach { (walletAddress, executions) ->
            logger.debug { "Sending TradesCreated to wallet $walletAddress" }
            broadcasterNotifications.add(
                BroadcasterNotification(
                    MyTradesCreated(executions.map { it.toTradeResponse() }),
                    recipient = walletAddress,
                ),
            )
        }

        // schedule MarketTradesCreated notification
        executionsCreatedByWallet
            .values
            .flatten()
            .filter { it.role == ExecutionRole.Taker }
            .sortedBy { it.trade.sequenceId }
            .groupBy { it.trade.marketGuid.value }
            .forEach { (marketId, takerExecutions) ->
                val seqNumber = KeyValueStore.incrementLong("WebsocketMsgSeqNumber:MarketTradesCreated:${marketId.value}")
                broadcasterNotifications.add(
                    BroadcasterNotification(
                        MarketTradesCreated(seqNumber, marketId, takerExecutions.map(MarketTradesCreated::Trade)),
                        recipient = null,
                    ),
                )
            }

        return tradesWithTakerOrders
    }

    private fun handleChangedOrders(changedOrders: List<OrderChanged>, timestamp: Instant, broadcasterNotifications: MutableList<BroadcasterNotification>) {
        // update all orders that have changed
        val orderChangedMap = changedOrders.mapNotNull { orderChanged ->
            if (orderChanged.disposition != OrderDisposition.Accepted) {
                logger.debug { "order updated for ${orderChanged.guid}, disposition ${orderChanged.disposition}" }
                orderChanged.guid to orderChanged
            } else {
                null
            }
        }.toMap()

        OrderEntity.listWithExecutionsForSequencerOrderIds(orderChangedMap.keys.toList()).forEach { (orderToUpdate, executions) ->
            val orderChanged = orderChangedMap.getValue(orderToUpdate.sequencerOrderId!!.value)

            when (orderChanged.disposition) {
                OrderDisposition.Accepted -> {
                    orderToUpdate.status = OrderStatus.Open
                }
                OrderDisposition.Filled -> {
                    orderToUpdate.status = OrderStatus.Filled
                }
                OrderDisposition.PartiallyFilled -> {
                    orderToUpdate.status = OrderStatus.Partial
                }
                OrderDisposition.Failed, OrderDisposition.UNRECOGNIZED -> {
                    orderToUpdate.status = OrderStatus.Failed
                }
                OrderDisposition.Canceled -> {
                    orderToUpdate.status = OrderStatus.Cancelled
                }
                OrderDisposition.Rejected -> {
                    orderToUpdate.status = OrderStatus.Rejected
                }
                OrderDisposition.AutoReduced -> {
                    orderToUpdate.autoReduced = true
                }
                null -> {}
            }

            orderChanged.newQuantityOrNull?.also { newQuantity ->
                orderToUpdate.originalAmount = orderToUpdate.amount
                orderToUpdate.amount = newQuantity.toBigInteger()
            }

            orderToUpdate.updatedAt = timestamp

            broadcasterNotifications.add(
                BroadcasterNotification(
                    MyOrderUpdated(orderToUpdate.toOrderResponse(executions)),
                    recipient = orderToUpdate.wallet.address,
                ),
            )
        }
    }

    private fun handleBalanceChanges(balanceChanges: List<xyz.funkybit.sequencer.proto.BalanceChange>, broadcasterNotifications: MutableList<BroadcasterNotification>) {
        logger.debug { "Calculating balance changes" }
        if (balanceChanges.isNotEmpty()) {
            val walletMap = WalletEntity.getBySequencerIds(
                balanceChanges.map { SequencerWalletId(it.wallet) }.toSet(),
            ).associateBy { it.sequencerId.value }

            logger.debug { "updating balances" }
            BalanceEntity.updateBalances(
                balanceChanges.mapNotNull { change ->
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
            logger.debug { "done updating balances" }
            walletMap.values.forEach {
                broadcasterNotifications.add(BroadcasterNotification.walletBalances(it))
            }
        }
        logger.debug { "Done calculating balance changes" }
    }

    private fun updateOrderBookSnapshot(ordersChanged: List<OrderChanged>, tradesWithTakerOrder: List<Pair<TradeEntity, OrderEntity>>, broadcasterNotifications: MutableList<BroadcasterNotification>) {
        val marketsWithOrderChanges = OrderEntity
            .getOrdersMarkets(ordersChanged.map { it.guid })
            .sortedBy { it.guid }

        logger.debug { "Got markets with order changes, calculating order book notifications" }
        marketsWithOrderChanges.forEach { market ->
            logger.debug { "Market $market" }
            val prevSnapshot = OrderBookSnapshot.get(market)
            logger.debug { "got old snapshot" }
            val newSnapshot = OrderBookSnapshot.calculate(market, tradesWithTakerOrder, prevSnapshot)
            logger.debug { "calculated new snapshot" }
            val diff = newSnapshot.diff(prevSnapshot)
            logger.debug { "calculated diff" }
            newSnapshot.save(market)
            logger.debug { "saved new snapshot" }
            val seqNumber = KeyValueStore.incrementLong("WebsocketMsgSeqNumber:OrderBookDiff:${market.id.value.value}")

            broadcasterNotifications.add(
                BroadcasterNotification(
                    OrderBook(market, newSnapshot),
                    recipient = null,
                ),
            )
            broadcasterNotifications.add(
                BroadcasterNotification(
                    OrderBookDiff(seqNumber, market, diff),
                    recipient = null,
                ),
            )
        }
        logger.debug { "Done calculating order book notifications" }
    }

    private fun updateOhlc(tradesWithTakerOrder: List<Pair<TradeEntity, OrderEntity>>, broadcasterNotifications: MutableList<BroadcasterNotification>) {
        val lastPriceByMarket = mutableMapOf<MarketId, BigDecimal>()
        val ohlcNotifications = tradesWithTakerOrder
            .fold(mutableMapOf<OrderId, MutableList<TradeEntity>>()) { acc, pair ->
                val trade = pair.first
                val orderId = pair.second.id.value
                acc[orderId] = acc.getOrDefault(orderId, mutableListOf()).also { it.add(trade) }
                acc
            }
            .forEach { (_, trades) ->
                val market = trades.first().market
                val marketPriceScale = market.tickSize.stripTrailingZeros().scale() + 1
                val sumOfAmounts = trades.sumOf { it.amount }
                val sumOfPricesByAmount = trades.sumOf { it.price * it.amount.toBigDecimal() }
                val weightedPrice = (sumOfPricesByAmount / sumOfAmounts.toBigDecimal()).setScale(marketPriceScale, RoundingMode.HALF_UP)

                val h24ClosePrice = OHLCEntity.findSingleByClosestStartTime(market.guid.value, OHLCDuration.P1M, OHLCDuration.P1M.durationStart(Clock.System.now() - 24.hours))?.close

                lastPriceByMarket[market.id.value] = weightedPrice

                OHLCEntity.updateWith(market.guid.value, trades.first().timestamp, weightedPrice, sumOfAmounts).forEach {
                    broadcasterNotifications.add(
                        BroadcasterNotification.pricesForMarketPeriods(
                            marketId = market.guid.value,
                            duration = it.duration,
                            ohlc = listOf(it),
                            full = false,
                            dailyChange = h24ClosePrice?.let { closePrice -> (weightedPrice - closePrice) / closePrice } ?: BigDecimal.ZERO,
                        ),
                    )
                }
            }

        logger.debug { "Done calculating ohlc notifications" }

        val markets = MarketEntity.all().toList()

        markets.forEach { m ->
            lastPriceByMarket[m.id.value]?.let {
                m.lastPrice = it
            }
        }

        return ohlcNotifications
    }

    private fun handleLimitsUpdates(limitsUpdates: List<LimitsUpdate>, broadcasterNotifications: MutableList<BroadcasterNotification>) {
        val walletsToNotifyAboutLimitsChanges = limitsUpdates
            .map { it.wallet }
            .distinct()
            .mapNotNull { WalletEntity.getBySequencerId(it.sequencerWalletId()) }

        val walletsBySequencerId = walletsToNotifyAboutLimitsChanges.associateBy { it.sequencerId.value }

        LimitEntity.update(
            limitsUpdates.map {
                val wallet = walletsBySequencerId.getValue(it.wallet)
                Pair(
                    wallet.guid.value,
                    MarketLimits(
                        MarketId(it.marketId),
                        it.base.toBigInteger(),
                        it.quote.toBigInteger(),
                    ),
                )
            },
        )

        walletsToNotifyAboutLimitsChanges.forEach { wallet ->
            broadcasterNotifications.add(
                BroadcasterNotification.limits(wallet),
            )
        }
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
