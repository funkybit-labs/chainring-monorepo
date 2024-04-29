package co.chainring.sequencer.apps.services

import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceChange
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.BroadcasterNotification
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.CreateOrderAssignment
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.ExchangeTransactionEntity
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.KeyValueStore
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
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
import co.chainring.core.utils.BroadcasterNotifications
import co.chainring.core.utils.add
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
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger
import java.math.RoundingMode

object SequencerResponseProcessorService {

    private val logger = KotlinLogging.logger {}

    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private val marketMap = mutableMapOf<MarketId, MarketEntity>()
    private val chainId by lazy { ChainEntity.all().first().id.value }
    private val keyName = "LastProcessedOutputIndex"

    fun getLastProcessedIndex() = transaction {
        KeyValueStore.getLong(keyName)
    }

    fun updateLastProcessedIndex(lastProcessedIndex: Long) = transaction {
        KeyValueStore.setLong(keyName, lastProcessedIndex)
    }

    fun onSequencerResponseReceived(response: SequencerResponse, request: SequencerRequest) {
        when (request.type) {
            SequencerRequest.Type.ApplyBalanceBatch -> {
                request.balanceBatch!!.withdrawalsList.forEach { withdrawal ->
                    WithdrawalEntity.findById(withdrawal.externalGuid.withdrawalId())?.let { withdrawalEntity ->
                        if (response.balancesChangedList.firstOrNull { it.wallet == withdrawal.wallet } == null) {
                            withdrawalEntity.update(WithdrawalStatus.Failed, error(response))
                        } else {
                            handleSequencerResponse(response, withdrawalEntity.wallet, listOf())
                            queueBlockchainTransactions(listOf(withdrawalEntity.toEip712Transaction()))
                        }
                    }
                }

                request.balanceBatch!!.depositsList.forEach { deposit ->
                    if (response.balancesChangedList.firstOrNull { it.wallet == deposit.wallet } == null) {
                        DepositEntity.findById(deposit.externalGuid.depositId())
                            ?.update(DepositStatus.Failed, error(response))
                    } else {
                        DepositEntity.findById(deposit.externalGuid.depositId())?.let {
                            handleSequencerResponse(response, it.wallet, listOf())
                        }
                    }
                }
            }

            SequencerRequest.Type.ApplyOrderBatch -> {
                if (response.error == SequencerError.None) {
                    WalletEntity.getBySequencerId(request.orderBatch.wallet.sequencerWalletId())?.let { wallet ->
                        handleOrderBatchUpdates(request.orderBatch, wallet)
                        handleSequencerResponse(
                            response,
                            wallet,
                            request.orderBatch.ordersToChangeList.map { it.guid },
                            cancelAll = request.orderBatch.cancelAll,
                        )
                    }
                }
            }

            else -> {}
        }
    }

    private fun error(response: SequencerResponse) =
        if (response.error != SequencerError.None) response.error.name else "Rejected by sequencer"

    private fun handleOrderBatchUpdates(orderBatch: OrderBatch, walletEntity: WalletEntity) {
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

            val updateAssignments = orderBatch.ordersToChangeList.map {
                UpdateOrderAssignment(
                    it.externalGuid.orderId(),
                    it.amount.toBigInteger(),
                    it.price.toBigDecimal(),
                    it.nonce.toBigInteger(),
                    it.signature.toEvmSignature(),
                )
            }

            OrderEntity.batchUpdate(
                getMarket(MarketId(orderBatch.marketId)),
                walletEntity,
                createAssignments,
                updateAssignments,
            )
            val orders = OrderEntity.listOrders(createAssignments.map { it.orderId }).map { it.toOrderResponse() }
            publishBroadcasterNotifications(
                orders.map { BroadcasterNotification(OrderCreated(it), walletEntity.address) },
            )
        }
    }

    private fun handleSequencerResponse(response: SequencerResponse, walletEntity: WalletEntity, ordersBeingUpdated: List<Long> = listOf(), cancelAll: Boolean = false) {
        val timestamp = Clock.System.now()

        val broadcasterNotifications: BroadcasterNotifications = mutableMapOf()

        // handle trades
        val tradesWithTakerOrder: List<Pair<TradeEntity, OrderEntity>> = response.tradesCreatedList.mapNotNull {
            logger.debug { "Trade Created ${it.buyGuid}, ${it.sellGuid}, ${it.amount.toBigInteger()} ${it.price.toBigDecimal()} " }
            val buyOrder = OrderEntity.findBySequencerOrderId(it.buyGuid)
            val sellOrder = OrderEntity.findBySequencerOrderId(it.sellGuid)

            if (buyOrder != null && sellOrder != null) {
                val tradeEntity = TradeEntity.create(
                    timestamp = timestamp,
                    market = buyOrder.market,
                    amount = it.amount.toBigInteger(),
                    price = it.price.toBigDecimal(),
                )

                // create executions for both
                listOf(buyOrder, sellOrder).forEach { order ->
                    val execution = OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = order,
                        tradeEntity = tradeEntity,
                        role = if (order.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(order.market.quoteSymbol.name),
                    )

                    execution.refresh(flush = true)
                    logger.debug { "Sending TradeCreated for order ${order.guid}" }
                    broadcasterNotifications.add(order.wallet.address, TradeCreated(execution.toTradeResponse()))
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
                            orderToUpdate.wallet.address,
                            OrderUpdated(orderToUpdate.toOrderResponse()),
                        )
                    }
                }
            }
        }
        if (cancelAll) {
            broadcasterNotifications.add(
                walletEntity.address,
                Orders(
                    OrderEntity
                        .listForWallet(walletEntity)
                        .map(OrderEntity::toOrderResponse),
                ),
            )
        }

        // update balance changes
        if (response.balancesChangedList.isNotEmpty()) {
            val walletMap =
                WalletEntity.getBySequencerIds(
                    response.balancesChangedList.map { SequencerWalletId(it.wallet) }
                        .toSet(),
                ).associateBy {
                    it.sequencerId.value
                }
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
                broadcasterNotifications.add(
                    it.address,
                    Balances(
                        BalanceEntity.balancesAsApiResponse(it).balances,
                    ),
                )
            }
        }

        // queue any blockchain txs for processing
        queueBlockchainTransactions(tradesWithTakerOrder.map { it.first.toEip712Transaction() })

        val ohlcNotifications = tradesWithTakerOrder
            .fold(mutableMapOf<OrderId, MutableList<TradeEntity>>()) { acc, pair ->
                val trade = pair.first
                val orderId = pair.second.id.value
                acc[orderId] = acc.getOrDefault(orderId, mutableListOf()).also { it -> it.add(trade) }
                acc
            }
            .map { (_, trades) ->
                val market = trades.first().market
                val marketPriceScale = market.tickSize.stripTrailingZeros().scale() + 1
                val sumOfAmounts = trades.sumOf { it.amount }
                val sumOfPricesByAmount = trades.sumOf { it.price * it.amount.toBigDecimal() }
                val weightedPrice = (sumOfPricesByAmount / sumOfAmounts.toBigDecimal()).setScale(marketPriceScale, RoundingMode.HALF_UP)

                OHLCEntity.updateWith(market.guid.value, trades.first().timestamp, weightedPrice, sumOfAmounts)
                    .map {
                        BroadcasterNotification.pricesForMarketPeriods(
                            market.guid.value,
                            it.duration,
                            listOf(it),
                            full = false,
                        )
                    }
            }.flatten()

        publishBroadcasterNotifications(
            broadcasterNotifications.flatMap { (address, notifications) ->
                notifications.map { BroadcasterNotification(it, address) }
            } + BroadcasterNotification.orderBooksForMarkets(
                OrderEntity
                    .getOrdersMarkets(response.ordersChangedList.map { it.guid })
                    .sortedBy { it.guid },
            ) + ohlcNotifications,
        )
    }

    private fun queueBlockchainTransactions(txs: List<EIP712Transaction>) {
        ExchangeTransactionEntity.createList(chainId, txs)
    }

    private fun getSymbol(asset: String): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            SymbolEntity.forChainAndName(chainId, asset)
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
