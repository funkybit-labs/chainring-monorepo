package co.chainring.sequencer.apps.services

import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.TradesCreated
import co.chainring.core.evm.ECHelper
import co.chainring.core.model.Address
import co.chainring.core.model.FeeRate
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceChange
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.BroadcasterNotification
import co.chainring.core.model.db.CreateOrderAssignment
import co.chainring.core.model.db.DepositEntity
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
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.model.db.toOrderResponse
import co.chainring.core.model.toEvmSignature
import co.chainring.core.sequencer.depositId
import co.chainring.core.sequencer.orderId
import co.chainring.core.sequencer.sequencerOrderId
import co.chainring.core.sequencer.sequencerWalletId
import co.chainring.core.sequencer.withdrawalId
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.BackToBackOrder
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.bidOfferStateOrNull
import co.chainring.sequencer.proto.newQuantityOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
                        handleSequencerResponse(response, ordersBeingUpdated = listOf())
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
                        handleSequencerResponse(response, ordersBeingUpdated = listOf())
                        depositEntity.markAsComplete()
                    }
                }

                if (request.balanceBatch.failedWithdrawalsList.isNotEmpty() ||
                    request.balanceBatch.failedSettlementsList.isNotEmpty()
                ) {
                    handleSequencerResponse(response, ordersBeingUpdated = listOf())
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
                            // TODO delete min/max allowed price OR apply logical dynamic range
                            minAllowedBidPrice = BigDecimal.ZERO
                            maxAllowedOfferPrice = Integer.MAX_VALUE.toBigDecimal()
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
                    it.externalGuid.orderId(),
                    it.nonce.toBigInteger(),
                    toOrderType(it.type),
                    toOrderSide(it.type),
                    it.amount.toBigInteger(),
                    it.levelIx,
                    it.signature.toEvmSignature(),
                    it.guid.sequencerOrderId(),
                    response.processingTime.toBigInteger(),
                )
            }

            val market = getMarket(MarketId(orderBatch.marketId))

            OrderEntity.batchCreate(market, wallet, createAssignments)

            val createdOrders = OrderEntity.listOrdersWithExecutions(createAssignments.map { it.orderId }).map { it.toOrderResponse() }
            val limitOrdersCreated = createdOrders.count { it is co.chainring.apps.api.model.Order.Limit } > 0

            publishBroadcasterNotifications(
                createdOrders.map { BroadcasterNotification(OrderCreated(it), wallet.address) } +
                    if (limitOrdersCreated) listOf(BroadcasterNotification.limits(wallet, market)) else emptyList(),
            )
        }
    }

    private fun handleBackToBackMarketOrder(backToBackOrder: BackToBackOrder, wallet: WalletEntity, response: SequencerResponse) {
        val createAssignment =
            CreateOrderAssignment(
                backToBackOrder.order.externalGuid.orderId(),
                backToBackOrder.order.nonce.toBigInteger(),
                OrderType.BackToBackMarket,
                toOrderSide(backToBackOrder.order.type),
                backToBackOrder.order.amount.toBigInteger(),
                backToBackOrder.order.levelIx,
                backToBackOrder.order.signature.toEvmSignature(),
                backToBackOrder.order.guid.sequencerOrderId(),
                response.processingTime.toBigInteger(),
            )

        val market = getMarket(MarketId(backToBackOrder.marketIdsList[0]))
        val backToBackMarket = getMarket(MarketId(backToBackOrder.marketIdsList[1]))

        OrderEntity.batchCreate(market, wallet, listOf(createAssignment), backToBackMarket = backToBackMarket)

        val createdOrder = OrderEntity.listOrdersWithExecutions(listOf(createAssignment.orderId)).map { it.toOrderResponse() }.first()

        publishBroadcasterNotifications(
            listOf(BroadcasterNotification(OrderCreated(createdOrder), wallet.address)),
        )
    }

    private fun handleSequencerResponse(response: SequencerResponse, ordersBeingUpdated: List<Long> = listOf(), orderIdsInRequest: List<Long> = listOf()) {
        val timestamp = if (response.createdAt > 0) {
            Instant.fromEpochMilliseconds(response.createdAt)
        } else {
            Clock.System.now()
        }

        val broadcasterNotifications = mutableListOf<BroadcasterNotification>()
        val limitsChanged = mutableSetOf<Pair<WalletEntity, MarketEntity>>()

        // handle trades
        val executionsCreatedByWallet = mutableMapOf<Address, MutableList<OrderExecutionEntity>>()
        val tradesWithTakerOrder: List<Pair<TradeEntity, OrderEntity>> = response.tradesCreatedList.mapNotNull { trade ->
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
                    responseSequence = response.sequence,
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
                    TradesCreated(executions.map { it.toTradeResponse() }),
                    recipient = walletAddress,
                ),
            )
        }

        // update all orders that have changed
        val orderChangedMap = response.ordersChangedList.mapNotNull { orderChanged ->
            if (ordersBeingUpdated.contains(orderChanged.guid) || orderChanged.disposition != OrderDisposition.Accepted) {
                logger.debug { "order updated for ${orderChanged.guid}, disposition ${orderChanged.disposition}" }
                orderChanged.guid to orderChanged
            } else {
                null
            }
        }.toMap()
        OrderEntity.listWithExecutionsForSequencerOrderIds(orderChangedMap.keys.toList()).forEach { (orderToUpdate, executions) ->
            val orderChanged = orderChangedMap.getValue(orderToUpdate.sequencerOrderId!!.value)
            orderToUpdate.updateStatus(OrderStatus.fromOrderDisposition(orderChanged.disposition))
            orderChanged.newQuantityOrNull?.also { newQuantity ->
                orderToUpdate.amount = newQuantity.toBigInteger()
            }
            broadcasterNotifications.add(
                BroadcasterNotification(
                    OrderUpdated(orderToUpdate.toOrderResponse(executions)),
                    recipient = orderToUpdate.wallet.address,
                ),
            )
            limitsChanged.add(Pair(orderToUpdate.wallet, orderToUpdate.market))
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

        val orderBookNotifications = BroadcasterNotification.orderBooksForMarkets(
            OrderEntity
                .getOrdersMarkets(response.ordersChangedList.map { it.guid })
                .sortedBy { it.guid },
        )

        val lastPriceByMarket = mutableMapOf<MarketId, BigDecimal>()
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

                lastPriceByMarket[market.id.value] = weightedPrice

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

        markets.forEach { m ->
            lastPriceByMarket[m.id.value]?.let {
                m.lastPrice = it
            }
        }

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
