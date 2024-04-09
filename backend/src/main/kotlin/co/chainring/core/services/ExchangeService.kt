package co.chainring.core.services

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.ExchangeError
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.utils.BalanceUtils
import co.chainring.core.websocket.Broadcaster
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.BigInteger

interface TxConfirmationCallback {
    fun onTxConfirmation(tx: EIP712Transaction, error: String?)
}

class ExchangeService(
    val blockchainClient: BlockchainClient,
    val broadcaster: Broadcaster,
) : TxConfirmationCallback {

    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private val logger = KotlinLogging.logger {}

    fun addOrder(
        wallet: WalletEntity,
        market: MarketEntity,
        apiRequest: CreateOrderApiRequest,
    ): Order {
        return transaction {
            val (orderEntity, order) = OrderEntity.findByNonce(nonce = apiRequest.nonce)
                ?.let { Pair(it, it.toOrderResponse()) }
                ?: OrderEntity.create(
                    nonce = apiRequest.nonce,
                    market = market,
                    wallet = wallet,
                    type = when (apiRequest) {
                        is CreateOrderApiRequest.Market -> OrderType.Market
                        is CreateOrderApiRequest.Limit -> OrderType.Limit
                    },
                    side = apiRequest.side,
                    amount = apiRequest.amount,
                    price = when (apiRequest) {
                        is CreateOrderApiRequest.Market -> null
                        is CreateOrderApiRequest.Limit -> checkPrice(market, apiRequest.price)
                    },
                    signature = apiRequest.signature,
                ).let {
                    it.refresh(flush = true)
                    val order = it.toOrderResponse()
                    broadcaster.notify(wallet.address, OrderCreated(order))
                    Pair(it, order)
                }

            if (orderEntity.sequencerOrderId == null) {
                val sequencerOrderId = orderEntity.guid.value.toSequencerId()
                val response = runBlocking {
                    logger.debug { "Adding order to sequencer ${sequencerOrderId.value}" }
                    SequencerClient.addOrder(
                        sequencerOrderId = sequencerOrderId.value,
                        marketId = apiRequest.marketId.value,
                        amount = apiRequest.amount,
                        price = when (apiRequest) {
                            is CreateOrderApiRequest.Market -> null
                            is CreateOrderApiRequest.Limit -> apiRequest.price.toString()
                        },
                        wallet = wallet.sequencerId.value,
                        orderType = when (apiRequest) {
                            is CreateOrderApiRequest.Market ->
                                when (apiRequest.side) {
                                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.MarketBuy
                                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.MarketSell
                                }

                            is CreateOrderApiRequest.Limit ->
                                when (apiRequest.side) {
                                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.LimitBuy
                                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.LimitSell
                                }
                        },
                    )
                }
                orderEntity.sequencerOrderId = sequencerOrderId

                // there are some failure cases where there is no corresponding order in the response,
                // so we treat that as a failed order otherwise we process response normally
                if (response.ordersChangedList.firstOrNull { it.guid == orderEntity.sequencerOrderId?.value } == null) {
                    orderEntity.updateStatus(OrderStatus.Failed)
                    broadcaster.notify(orderEntity.wallet.address, OrderUpdated(orderEntity.toOrderResponse()))
                } else {
                    handleSequencerResponse(response)
                }
            }
            order
        }
    }

    fun deposit(wallet: WalletEntity, symbol: String, amount: BigInteger) {
        transaction {
            val response = runBlocking {
                SequencerClient.deposit(wallet.sequencerId.value, Asset(symbol), amount)
            }
            val symbolId = getSymbol(symbol).guid.value
            BalanceUtils.updateBalances(
                listOf(BalanceUtils.BalanceChange(wallet.id.value, symbolId, amount, null)),
                BalanceType.Exchange,
            )
            if (response.balancesChangedList.isEmpty()) {
                // if this did not result in a balance change fail the withdrawal since sequencer rejected it for some reason
                throw ExchangeError("Unable to update the balance")
            } else {
                handleSequencerResponse(response)
            }
        }
    }

    fun withdraw(withdrawTx: EIP712Transaction.WithdrawTx): WithdrawalId {
        return transaction {
            val withdrawalEntity = WithdrawalEntity.create(
                withdrawTx.nonce,
                blockchainClient.chainId,
                WalletEntity.getOrCreate(withdrawTx.sender),
                withdrawTx.token,
                withdrawTx.amount,
                withdrawTx.signature,
            )

            val response = runBlocking {
                SequencerClient.withdraw(
                    withdrawalEntity.wallet.sequencerId.value,
                    Asset(withdrawalEntity.symbol.name),
                    withdrawalEntity.amount,
                )
            }
            if (response.balancesChangedList.isEmpty()) {
                // if this did not result in a balance change fail the withdrawal since sequencer rejected it for some reason
                withdrawalEntity.update(WithdrawalStatus.Failed, "Rejected by sequencer")
            } else {
                handleSequencerResponse(response)
                blockchainClient.queueTransactions(listOf(withdrawalEntity.toEip712Transaction()))
            }
            withdrawalEntity.guid.value
        }
    }

    fun cancelOrder(orderEntity: OrderEntity) {
        val response = runBlocking {
            SequencerClient.cancelOrder(
                sequencerOrderId = orderEntity.sequencerOrderId!!.value,
                marketId = orderEntity.market.guid.value.value,
            )
        }
        handleSequencerResponse(response)
    }

    fun cancelOpenOrders(walletEntity: WalletEntity) {
        transaction {
            val openOrders = OrderEntity.listOpenOrders(walletEntity)
            if (openOrders.isNotEmpty()) {
                runBlocking {
                    openOrders.groupBy { it.marketGuid }.forEach { entry ->
                        val sequencerOrderIds = entry.value.mapNotNull { it.sequencerOrderId?.value }
                        SequencerClient.cancelOrders(sequencerOrderIds, entry.key.value.value)
                    }
                }
                OrderEntity.cancelAll(walletEntity)
                broadcaster.sendOrders(walletEntity.address)
            }
        }
    }

    private fun handleSequencerResponse(response: SequencerResponse) {
        val timestamp = Clock.System.now()

        // handle trades
        val blockchainTxs = response.tradesCreatedList.mapNotNull {
            logger.debug { "Creating trade for orders ${it.buyGuid} ${it.sellGuid}" }
            OrderEntity.findBySequencerOrderId(it.buyGuid)?.let { buyOrder ->
                OrderEntity.findBySequencerOrderId(it.sellGuid)?.let { sellOrder ->
                    val tradeEntity = TradeEntity.create(
                        timestamp = timestamp,
                        market = buyOrder.market,
                        amount = it.amount.toBigInteger(),
                        price = it.price.toBigDecimal(),
                    )
                    // create executions for both
                    OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = buyOrder,
                        tradeEntity = tradeEntity,
                        role = if (buyOrder.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(buyOrder.market.quoteSymbol.name),
                    ).refresh(flush = true)
                    OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = sellOrder,
                        tradeEntity = tradeEntity,
                        role = if (sellOrder.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(sellOrder.market.quoteSymbol.name),
                    ).refresh(flush = true)

                    // build the transaction to settle
                    tradeEntity.toEip712Transaction()
                }
            }
        }

        // update all orders that have changed from an Accepted disposition
        response.ordersChangedList.forEach {
            if (it.disposition != OrderDisposition.Accepted) {
                logger.debug { "order updated for ${it.guid}, disposition ${it.disposition}" }
                OrderEntity.findBySequencerOrderId(it.guid)?.let { orderToUpdate ->
                    orderToUpdate.updateStatus(OrderStatus.fromOrderDisposition(it.disposition))
                    logger.debug { "sending order updated for ${it.guid}, disposition ${it.disposition}" }
                    broadcaster.notify(orderToUpdate.wallet.address, OrderUpdated(orderToUpdate.toOrderResponse()))
                }
            }
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
            BalanceUtils.updateBalances(
                response.balancesChangedList.map { change ->
                    BalanceUtils.BalanceChange(
                        walletId = walletMap[change.wallet]!!.guid.value,
                        symbolId = getSymbol(change.asset).guid.value,
                        delta = change.delta.toBigInteger(),
                    )
                },
                BalanceType.Available,
            )
        }

        // queue any blockchain txs for processing
        blockchainClient.queueTransactions(blockchainTxs)
    }

    private fun getSymbol(asset: String): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            SymbolEntity.forChainAndName(blockchainClient.chainId, asset)
        }
    }

    private fun getContractAddress(asset: String): Address {
        return getSymbol(asset).contractAddress ?: Address.zero
    }

    private fun checkPrice(market: MarketEntity, price: BigDecimal): BigDecimal {
        if (BigDecimal.ZERO.compareTo(price.remainder(market.tickSize)) != 0) {
            throw ExchangeError("Order price is not a multiple of tick size")
        }
        return price
    }

    override fun onTxConfirmation(tx: EIP712Transaction, error: String?) {
        transaction {
            when (tx) {
                is EIP712Transaction.WithdrawTx -> {
                    WithdrawalEntity.findPendingByWalletAndNonce(
                        WalletEntity.getByAddress(tx.sender)!!,
                        tx.nonce,
                    )?.let {
                        it.update(
                            status = error?.let { WithdrawalStatus.Failed }
                                ?: WithdrawalStatus.Complete,
                            error = error,
                        )
                        val finalBalance = runBlocking {
                            blockchainClient.getExchangeBalance(
                                it.wallet.address,
                                it.symbol.contractAddress ?: Address.zero,
                            )
                        }
                        BalanceUtils.updateBalances(
                            listOf(
                                BalanceUtils.BalanceChange(
                                    it.wallet.id.value,
                                    it.symbol.id.value,
                                    it.amount,
                                    finalBalance,
                                ),
                            ),
                            BalanceType.Exchange,
                        )
                    }
                }

                is EIP712Transaction.Order -> {}

                is EIP712Transaction.Trade -> {
                    transaction {
                        TradeEntity.findById(tx.tradeId)?.let { tradeEntity ->
                            if (error != null) {
                                BlockchainClient.logger.error { "settlement failed for ${tx.tradeId} - error is <$error>" }
                                tradeEntity.failSettlement()
                            } else {
                                BlockchainClient.logger.debug { "settlement completed for ${tx.tradeId}" }
                                tradeEntity.settle()
                            }
                            // update the onchain balances
                            val executions = OrderExecutionEntity.findForTrade(tradeEntity)
                            val wallets = executions.map { it.order.wallet }
                            val symbols = listOf(
                                executions.first().order.market.baseSymbol,
                                executions.first().order.market.quoteSymbol,
                            )
                            val finalExchangeBalances = runBlocking {
                                blockchainClient.getExchangeBalances(
                                    wallets.map { it.address },
                                    symbols.map { getContractAddress(it.name) },
                                )
                            }

                            BalanceUtils.updateBalances(
                                wallets.map { wallet ->
                                    symbols.map { symbol ->
                                        BalanceUtils.BalanceChange(
                                            walletId = wallet.guid.value,
                                            symbolId = symbol.guid.value,
                                            finalAmount = finalExchangeBalances[wallet.address]!![
                                                getContractAddress(
                                                    symbol.name,
                                                ),
                                            ]!!,
                                        )
                                    }
                                }.flatten(),
                                BalanceType.Exchange,
                            )
                        }
                    }
                }
            }
        }
    }
}
