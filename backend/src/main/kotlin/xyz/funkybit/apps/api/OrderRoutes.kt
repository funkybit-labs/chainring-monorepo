package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
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
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.BatchOrdersApiResponse
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.OrdersApiResponse
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.apps.api.model.orderNotFoundError
import xyz.funkybit.apps.api.model.processingError
import xyz.funkybit.apps.api.model.unexpectedError
import xyz.funkybit.apps.api.services.ExchangeApiService
import xyz.funkybit.core.model.db.ClientOrderId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.toOrderResponse

class OrderRoutes(
    private val exchangeApiService: ExchangeApiService,
    private val maxBatchSize: Int = System.getenv("BATCH_ORDERS_MAX_SIZE")?.toIntOrNull() ?: 100,
) {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderId", "Order Id")
    private val orderIdOrClientOrderIdPathParam = Path.of("orderIdOrClientOrderId", "Order Id or Client Order Id")

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
            val wallet = request.principal
            val response = exchangeApiService.addOrder(wallet, apiRequest)
            if (response.requestStatus == RequestStatus.Accepted) {
                Response(Status.CREATED).with(
                    responseBody of response,
                )
            } else {
                response.error?.let { errorResponse(Status.UNPROCESSABLE_ENTITY, it) } ?: unexpectedError()
            }
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
                    val wallet = request.principal
                    val response = exchangeApiService.cancelOrder(wallet, apiRequest)
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

        return "orders" / orderIdOrClientOrderIdPathParam meta {
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
        } bindContract Method.GET to { orderIdOrClientOrderId ->
            { request ->
                transaction {
                    val order = if (orderIdOrClientOrderId.startsWith("external:")) {
                        val clientOrderId = ClientOrderId(orderIdOrClientOrderId.removePrefix("external:"))
                        OrderEntity.findByClientOrderIdForUser(clientOrderId, request.principal.userGuid)
                    } else {
                        OrderEntity.findByIdForUser(OrderId(orderIdOrClientOrderId), request.principal.userGuid)
                    }

                    when (order) {
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
                OrderEntity.listWithExecutionsForUser(
                    request.principal.userGuid,
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
            exchangeApiService.cancelOpenOrders(request.principal.userGuid)

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
                        Examples.cancelOrderRequest,
                    ),
                ),
            )
            returning(
                Status.OK,
                responseBody to BatchOrdersApiResponse(
                    createdOrders = listOf(Examples.createMarketOrderResponse, Examples.createLimitOrderResponse),
                    canceledOrders = listOf(Examples.cancelOrderResponse),
                ),
            )
        } bindContract Method.POST to { request ->
            transaction {
                val wallet = request.principal
                val batchOrdersRequest = requestBody(request)

                val totalOrders = batchOrdersRequest.createOrders.size + batchOrdersRequest.cancelOrders.size
                if (totalOrders > maxBatchSize) {
                    return@transaction processingError(ReasonCode.BatchSizeExceeded, "Batch size exceeds the maximum limit of $maxBatchSize orders.")
                }

                Response(Status.OK).with(
                    responseBody of exchangeApiService.orderBatch(wallet, batchOrdersRequest),
                )
            }
        }
    }
}
