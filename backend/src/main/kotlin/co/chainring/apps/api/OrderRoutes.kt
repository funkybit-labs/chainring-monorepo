package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.badRequestError
import co.chainring.apps.api.model.marketNotSupportedError
import co.chainring.apps.api.model.orderIsClosedError
import co.chainring.apps.api.model.orderNotFoundError
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.websocket.Broadcaster
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.OrderDisposition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.contract.ContractRoute
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.string
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger

object OrderRoutes {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderOd", "Order Id")

    fun createOrder(broadcaster: Broadcaster, blockchainClient: BlockchainClient): ContractRoute {
        val requestBody = Body.auto<CreateOrderApiRequest>().toLens()
        val responseBody = Body.auto<Order>().toLens()

        return "orders" meta {
            operationId = "create-order"
            summary = "Create order"
            security = signedTokenSecurity
            receiving(
                requestBody to Examples.createMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.createLimitOrderRequest,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.POST to { request ->
            val apiRequest: CreateOrderApiRequest = requestBody(request)

            transaction {
                val market = MarketEntity.findById(apiRequest.marketId)
                val isSignatureValid = validateSignature(market, request.principal, apiRequest, blockchainClient)

                when {
                    market == null -> marketNotSupportedError

                    !isSignatureValid -> badRequestError(ReasonCode.SignatureNotValid, "Signature not verified")

                    else -> {
                        val (orderEntity, order) = OrderEntity.findByNonce(nonce = apiRequest.nonce)?.let { Pair(it, it.toOrderResponse()) }
                            ?: OrderEntity.create(
                                nonce = apiRequest.nonce,
                                market = market,
                                ownerAddress = request.principal,
                                type = when (apiRequest) {
                                    is CreateOrderApiRequest.Market -> OrderType.Market
                                    is CreateOrderApiRequest.Limit -> OrderType.Limit
                                },
                                side = apiRequest.side,
                                amount = apiRequest.amount,
                                price = when (apiRequest) {
                                    is CreateOrderApiRequest.Market -> null
                                    is CreateOrderApiRequest.Limit -> apiRequest.price
                                },
                                signature = apiRequest.signature,
                            ).let {
                                it.refresh(flush = true)
                                val order = it.toOrderResponse()
                                broadcaster.notify(request.principal, OrderCreated(order))
                                Pair(it, order)
                            }

                        if (orderEntity.sequencerOrderId == null) {
                            // send to Sequencer and handle response
                            sendOrderToSequencer(
                                orderEntity,
                                market,
                                request,
                                apiRequest,
                                blockchainClient,
                                broadcaster,
                            )
                        }

                        Response(Status.CREATED).with(
                            responseBody of order,
                        )
                    }
                }
            }
        }
    }

    fun updateOrder(broadcaster: Broadcaster): ContractRoute {
        val requestBody = Body.auto<UpdateOrderApiRequest>().toLens()
        val responseBody = Body.auto<Order>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "update-order"
            summary = "Update order"
            security = signedTokenSecurity
            receiving(
                requestBody to Examples.updateMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.updateLimitOrderRequest,
            )
            returning(
                Status.OK,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.OK,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.PATCH to { orderId ->
            fun handle(request: Request): Response {
                val apiRequest: UpdateOrderApiRequest = requestBody(request)
                return transaction {
                    val orderEntity = OrderEntity.findById(orderId)
                    when {
                        orderEntity == null -> orderNotFoundError
                        orderEntity.status.isFinal() -> orderIsClosedError

                        else -> {
                            orderEntity.update(
                                amount = apiRequest.amount,
                                price = when (apiRequest) {
                                    is UpdateOrderApiRequest.Market -> null
                                    is UpdateOrderApiRequest.Limit -> apiRequest.price
                                },
                            )
                            orderEntity.refresh(flush = true)

                            val order = orderEntity.toOrderResponse().also {
                                broadcaster.notify(request.principal, OrderUpdated(it))
                            }

                            Response(Status.OK).with(
                                responseBody of order,
                            )
                        }
                    }
                }
            }
            ::handle
        }
    }

    fun cancelOrder(broadcaster: Broadcaster): ContractRoute {
        return "orders" / orderIdPathParam meta {
            operationId = "cancel-order"
            summary = "Cancel order"
            security = signedTokenSecurity
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { orderId ->
            { request: Request ->
                transaction {
                    val order = OrderEntity.findById(orderId)
                    when {
                        order == null -> orderNotFoundError
                        order.status == OrderStatus.Cancelled -> Response(Status.NO_CONTENT)
                        order.status.isFinal() -> orderIsClosedError

                        else -> {
                            order.cancel()
                            order.refresh(flush = true)
                            broadcaster.notify(request.principal, OrderUpdated(order.toOrderResponse()))
                            Response(Status.NO_CONTENT)
                        }
                    }
                }
            }
        }
    }

    fun getOrder(): ContractRoute {
        val responseBody = Body.auto<Order>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "get-order"
            summary = "Get order"
            security = signedTokenSecurity
            returning(
                Status.OK,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.OK,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.GET to { orderId ->
            { _: Request ->
                transaction {
                    when (val order = OrderEntity.findById(orderId)) {
                        null -> orderNotFoundError
                        else -> Response(Status.OK).with(
                            responseBody of order.toOrderResponse(),
                        )
                    }
                }
            }
        }
    }

    fun listOrders(): ContractRoute {
        val responseBody = Body.auto<OrdersApiResponse>().toLens()

        return "orders" meta {
            operationId = "list-orders"
            summary = "List orders"
            security = signedTokenSecurity
            returning(
                Status.OK,
                responseBody to OrdersApiResponse(
                    orders = listOf(Examples.marketOrderResponse, Examples.limitOrderResponse),
                ),
            )
        } bindContract Method.GET to { request: Request ->
            val orders = transaction {
                OrderEntity.listOrders(request.principal).map { it.toOrderResponse() }
            }
            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    orders = orders,
                ),
            )
        }
    }

    fun cancelOpenOrders(broadcaster: Broadcaster): ContractRoute {
        return "orders" meta {
            operationId = "cancel-open-orders"
            summary = "Cancel open orders"
            security = signedTokenSecurity
        } bindContract Method.DELETE to { request ->
            transaction {
                OrderEntity.cancelAll(request.principal)
            }
            broadcaster.sendOrders(request.principal)
            Response(Status.NO_CONTENT)
        }
    }

    fun batchOrders(broadcaster: Broadcaster): ContractRoute {
        val requestBody = Body.auto<BatchOrdersApiRequest>().toLens()
        val responseBody = Body.auto<OrdersApiResponse>().toLens()

        return "batch/orders" meta {
            operationId = "batch-orders"
            summary = "Manage orders in batch"
            security = signedTokenSecurity
        } bindContract Method.POST to { request ->
            val apiRequest: BatchOrdersApiRequest = requestBody(request)

            logger.debug {
                Json.encodeToString(apiRequest)
            }

            // TODO: broadcast affected orders

            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    emptyList(),
                ),
            )
        }
    }

    fun listTrades(): ContractRoute {
        val responseBody = Body.auto<TradesApiResponse>().toLens()

        return "trades" meta {
            operationId = "list-trades"
            summary = "List trades"
            security = signedTokenSecurity
            queries += Query.string().optional("before-timestamp", "Return trades executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
        } bindContract Method.GET to { request ->
            val timestamp = request.query("before-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            val trades = transaction {
                OrderExecutionEntity.listExecutions(
                    ownerAddress = request.principal,
                    beforeTimestamp = timestamp,
                    limit = limit,
                ).map { it.toTradeResponse() }
            }

            Response(Status.OK).with(
                responseBody of TradesApiResponse(trades = trades),
            )
        }
    }

    private fun validateSignature(market: MarketEntity?, walletAddress: Address, apiRequest: CreateOrderApiRequest, blockchainClient: BlockchainClient): Boolean {
        return market?.let {
            val tx = when (apiRequest) {
                is CreateOrderApiRequest.Market -> apiRequest.toEip712Transaction(walletAddress, it.baseSymbol.contractAddress, it.quoteSymbol.contractAddress)
                is CreateOrderApiRequest.Limit -> apiRequest.toEip712Transaction(walletAddress, it.baseSymbol.contractAddress, it.quoteSymbol.contractAddress, it.quoteSymbol.decimals.toInt())
            }

            val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
            contractAddress?.let { verifyingContract ->
                ECHelper.isValidSignature(
                    EIP712Helper.computeHash(
                        tx,
                        blockchainClient.chainId,
                        verifyingContract,
                    ),
                    tx.signature,
                    walletAddress,
                )
            } ?: false
        } ?: false
    }

    private fun sendOrderToSequencer(
        orderEntity: OrderEntity,
        market: MarketEntity,
        request: Request,
        apiRequest: CreateOrderApiRequest,
        blockchainClient: BlockchainClient,
        broadcaster: Broadcaster,
    ) {
        val sequencerOrderId = orderEntity.guid.value.toSequencerId()
        val response = runBlocking {
            SequencerClient.addOrder(
                sequencerOrderId = sequencerOrderId,
                marketId = apiRequest.marketId.value,
                amount = apiRequest.amount,
                price = when (apiRequest) {
                    is CreateOrderApiRequest.Market -> null
                    is CreateOrderApiRequest.Limit -> apiRequest.price.toString()
                },
                wallet = request.principal.value.toSequencerId(),
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

        val timestamp = Clock.System.now()

        orderEntity.sequencerOrderId = sequencerOrderId

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
                        feeSymbol = Symbol(market.quoteSymbol.name),
                    ).refresh(flush = true)
                    OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = sellOrder,
                        tradeEntity = tradeEntity,
                        role = if (sellOrder.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(market.quoteSymbol.name),
                    ).refresh(flush = true)

                    // build the transaction to settle
                    tradeEntity.toEip712Transaction()
                }
            }
        }

        // there are some cases where there is no corresponding order in the response, so need to treat that as a failed
        // update this order's status
        val dispositionForThisOrder = response.ordersChangedList.firstOrNull { it.guid == orderEntity.sequencerOrderId }?.disposition ?: OrderDisposition.Failed
        if (dispositionForThisOrder != OrderDisposition.Accepted) {
            logger.debug { "Sending a notify for order updated for ${orderEntity.sequencerOrderId}, disposition: $dispositionForThisOrder" }
            orderEntity.updateStatus(OrderStatus.fromOrderDisposition(dispositionForThisOrder))
            broadcaster.notify(orderEntity.ownerAddress, OrderUpdated(orderEntity.toOrderResponse()))
        }

        // now update all the other orders that have changed
        response.ordersChangedList.filterNot { it.guid == orderEntity.sequencerOrderId }.forEach {
            logger.debug { "order updated for ${it.guid}, disposition ${it.disposition}" }
            OrderEntity.findBySequencerOrderId(it.guid)?.let { orderToUpdate ->
                orderToUpdate.updateStatus(OrderStatus.fromOrderDisposition(it.disposition))
                broadcaster.notify(orderToUpdate.ownerAddress, OrderUpdated(orderToUpdate.toOrderResponse()))
            }
        }

        // queue any blockchain txs for processing
        blockchainClient.queueTransactions(blockchainTxs)
    }
}
