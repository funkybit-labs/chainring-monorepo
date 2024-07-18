package co.chainring.apps.api

import co.chainring.apps.api.Examples.cancelOrderResponse
import co.chainring.apps.api.Examples.createLimitOrderResponse
import co.chainring.apps.api.Examples.createMarketOrderResponse
import co.chainring.apps.api.Examples.updateLimitOrderResponse
import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.BatchOrdersApiResponse
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.UpdateOrderApiResponse
import co.chainring.apps.api.model.errorResponse
import co.chainring.apps.api.model.orderNotFoundError
import co.chainring.apps.api.model.processingError
import co.chainring.apps.api.model.unexpectedError
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.toOrderResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
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

class OrderRoutes(private val exchangeApiService: ExchangeApiService) {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderId", "Order Id")

    fun createOrder(): ContractRoute {
        val requestBody = Body.auto<CreateOrderApiRequest>().toLens()
        val responseBody = Body.auto<CreateOrderApiResponse>().toLens()

        return "orders" meta {
            operationId = "create-order"
            summary = "Create order"
            security = signedTokenSecurity
            tags += listOf(Tag("order"))
            receiving(
                requestBody to Examples.createMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.createLimitOrderRequest,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.createMarketOrderResponse,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.createLimitOrderResponse,
            )
        } bindContract Method.POST to { request ->
            val apiRequest: CreateOrderApiRequest = requestBody(request)
            val response = exchangeApiService.addOrder(request.principal, apiRequest)
            if (response.requestStatus == RequestStatus.Accepted) {
                Response(Status.CREATED).with(
                    responseBody of response,
                )
            } else {
                response.error?.let { errorResponse(Status.UNPROCESSABLE_ENTITY, it) } ?: unexpectedError()
            }
        }
    }

    fun updateOrder(): ContractRoute {
        val requestBody = Body.auto<UpdateOrderApiRequest>().toLens()
        val responseBody = Body.auto<UpdateOrderApiResponse>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "update-order"
            summary = "Update order"
            security = signedTokenSecurity
            tags += listOf(Tag("order"))
            receiving(
                requestBody to Examples.updateLimitOrderRequest,
            )
            returning(
                Status.OK,
                responseBody to Examples.updateLimitOrderResponse,
            )
        } bindContract Method.PATCH to { orderId ->
            fun handle(request: Request): Response {
                val apiRequest: UpdateOrderApiRequest = requestBody(request)
                return if (orderId != apiRequest.orderId) {
                    processingError("Invalid order id")
                } else {
                    val response = exchangeApiService.updateOrder(request.principal, apiRequest)
                    if (response.requestStatus == RequestStatus.Accepted) {
                        Response(Status.OK).with(
                            responseBody of response,
                        )
                    } else {
                        response.error?.let { errorResponse(Status.UNPROCESSABLE_ENTITY, it) } ?: unexpectedError()
                    }
                }
            }
            ::handle
        }
    }

    fun cancelOrder(): ContractRoute {
        val requestBody = Body.auto<CancelOrderApiRequest>().toLens()
        return "orders" / orderIdPathParam meta {
            operationId = "cancel-order"
            summary = "Cancel order"
            security = signedTokenSecurity
            tags += listOf(Tag("order"))
            receiving(
                requestBody to Examples.cancelOrderRequest,
            )
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { orderId ->
            { request: Request ->
                val apiRequest: CancelOrderApiRequest = requestBody(request)
                if (orderId != apiRequest.orderId) {
                    processingError("Invalid order id")
                } else {
                    val response = exchangeApiService.cancelOrder(request.principal, apiRequest)
                    if (response.requestStatus == RequestStatus.Accepted) {
                        Response(Status.NO_CONTENT)
                    } else {
                        response.error?.let { errorResponse(Status.UNPROCESSABLE_ENTITY, it) } ?: unexpectedError()
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
            tags += listOf(Tag("order"))
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
                            responseBody of order.toOrderResponse(OrderExecutionEntity.findForOrder(order)),
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
            tags += listOf(Tag("order"))
            queries += Query.string().optional("statuses", "Comma-separated list of order statuses to filter on")
            queries += Query.string().optional("marketId", "Market id to filter on")
            returning(
                Status.OK,
                responseBody to OrdersApiResponse(
                    orders = listOf(Examples.marketOrderResponse, Examples.limitOrderResponse),
                ),
            )
        } bindContract Method.GET to { request: Request ->
            val orders = transaction {
                OrderEntity.listWithExecutionsForWallet(
                    WalletEntity.getOrCreate(request.principal),
                    request.query("statuses")?.let { statuses ->
                        statuses.split(",").mapNotNull {
                            try {
                                OrderStatus.valueOf(it)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                    } ?: emptyList(),
                    request.query("marketId")?.let {
                        MarketId(it)
                    },
                ).map { it.toOrderResponse() }
            }
            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    orders = orders,
                ),
            )
        }
    }

    fun cancelOpenOrders(): ContractRoute {
        return "orders" meta {
            operationId = "cancel-open-orders"
            summary = "Cancel open orders"
            security = signedTokenSecurity
            tags += listOf(Tag("order"))
            returning(Status.NO_CONTENT)
        } bindContract Method.DELETE to { request ->
            exchangeApiService.cancelOpenOrders(
                transaction { WalletEntity.getOrCreate(request.principal) },
            )
            Response(Status.NO_CONTENT)
        }
    }

    fun batchOrders(): ContractRoute {
        val requestBody = Body.auto<BatchOrdersApiRequest>().toLens()
        val responseBody = Body.auto<BatchOrdersApiResponse>().toLens()

        return "batch/orders" meta {
            operationId = "batch-orders"
            summary = "Manage orders in batch"
            security = signedTokenSecurity
            tags += listOf(Tag("order"))
            receiving(
                requestBody to BatchOrdersApiRequest(
                    MarketId("BTC/ETH"),
                    listOf(
                        Examples.createLimitOrderRequest,
                    ),
                    listOf(
                        Examples.updateLimitOrderRequest,
                    ),
                    listOf(
                        Examples.cancelOrderRequest,
                    ),
                ),
            )
            returning(
                Status.OK,
                responseBody to BatchOrdersApiResponse(
                    createdOrders = listOf(createMarketOrderResponse, createLimitOrderResponse),
                    updatedOrders = listOf(updateLimitOrderResponse),
                    canceledOrders = listOf(cancelOrderResponse),
                ),
            )
        } bindContract Method.POST to { request ->
            Response(Status.OK).with(
                responseBody of exchangeApiService.orderBatch(request.principal, requestBody(request)),
            )
        }
    }

    fun listTrades(): ContractRoute {
        val responseBody = Body.auto<TradesApiResponse>().toLens()

        return "trades" meta {
            operationId = "list-trades"
            summary = "List trades"
            security = signedTokenSecurity
            tags += listOf(Tag("trade"))
            queries += Query.string().optional("before-timestamp", "Return trades executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
            returning(
                Status.OK,
                responseBody to TradesApiResponse(
                    listOf(
                        Trade(
                            TradeId("trade_1234"),
                            Clock.System.now(),
                            OrderId("1234"),
                            MarketId("BTC/ETH"),
                            ExecutionRole.Taker,
                            counterOrderId = OrderId("4321"),
                            OrderSide.Buy,
                            12345.toBigInteger(),
                            17.61.toBigDecimal(),
                            500.toBigInteger(),
                            Symbol("ETH"),
                            SettlementStatus.Pending,
                        ),
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            val timestamp = request.query("before-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            val trades = transaction {
                OrderExecutionEntity.listForWallet(
                    wallet = WalletEntity.getOrCreate(request.principal),
                    beforeTimestamp = timestamp,
                    limit = limit,
                ).map { it.toTradeResponse() }
            }

            Response(Status.OK).with(
                responseBody of TradesApiResponse(trades = trades),
            )
        }
    }
}
