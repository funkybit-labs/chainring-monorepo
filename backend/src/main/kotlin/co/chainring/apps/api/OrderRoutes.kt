package co.chainring.apps.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.errorResponse
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import io.github.oshai.kotlinlogging.KotlinLogging
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

object OrderRoutes {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderOd", "Order Id")

    fun createOrder(): ContractRoute {
        val requestBody = Body.auto<CreateOrderApiRequest>().toLens()
        val responseBody = Body.auto<OrderApiResponse>().toLens()

        return "orders" meta {
            operationId = "create-order"
            summary = "Create order"
            receiving(
                requestBody to Examples.crateMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.crateLimitOrderRequest,
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
                when (val market = MarketEntity.findById(apiRequest.marketId)) {
                    null -> errorResponse(Status.BAD_REQUEST, ApiError(ReasonCode.MarketNotSupported, "Market is not supported"))

                    else -> {
                        val order = OrderEntity.findByNonce(nonce = apiRequest.nonce)
                            ?: OrderEntity.create(
                                nonce = apiRequest.nonce,
                                market = market,
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
                            )

                        Response(Status.CREATED).with(
                            responseBody of order.toOrderResponse(),
                        )
                    }
                }
            }
        }
    }

    fun updateOrder(): ContractRoute {
        val requestBody = Body.auto<UpdateOrderApiRequest>().toLens()
        val responseBody = Body.auto<OrderApiResponse>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "update-order"
            summary = "Update order"
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
                    val order = OrderEntity.findById(orderId)
                    when {
                        order == null -> errorResponse(Status.NOT_FOUND, ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))
                        order.status.isFinal() -> errorResponse(Status.BAD_REQUEST, ApiError(ReasonCode.OrderIsClosed, "Order is already finalized"))

                        else -> {
                            order.update(
                                amount = apiRequest.amount,
                                price = when (apiRequest) {
                                    is UpdateOrderApiRequest.Market -> null
                                    is UpdateOrderApiRequest.Limit -> apiRequest.price
                                },
                            )

                            Response(Status.OK).with(
                                responseBody of order.toOrderResponse(),
                            )
                        }
                    }
                }
            }
            ::handle
        }
    }

    fun cancelOrder(): ContractRoute {
        return "orders" / orderIdPathParam meta {
            operationId = "cancel-order"
            summary = "Cancel order"
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { orderId ->
            { _: Request ->
                transaction {
                    val order = OrderEntity.findById(orderId)
                    when {
                        order == null -> errorResponse(Status.NOT_FOUND, ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))
                        order.status == OrderStatus.Cancelled -> Response(Status.NO_CONTENT)
                        order.status.isFinal() -> errorResponse(Status.BAD_REQUEST, ApiError(ReasonCode.OrderIsClosed, "Order is already finalized"))

                        else -> {
                            order.cancel()
                            Response(Status.NO_CONTENT)
                        }
                    }
                }
            }
        }
    }

    fun getOrder(): ContractRoute {
        val responseBody = Body.auto<OrderApiResponse>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "get-order"
            summary = "Get order"
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
                        null -> errorResponse(Status.NOT_FOUND, ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))
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
            returning(
                Status.OK,
                responseBody to OrdersApiResponse(
                    orders = listOf(Examples.marketOrderResponse, Examples.limitOrderResponse),
                ),
            )
        } bindContract Method.GET to { _ ->
            val orders = transaction {
                // TODO filter by current user
                OrderEntity.findAll()
            }
            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    orders = orders.map { it.toOrderResponse() },
                ),
            )
        }
    }

    fun cancelOpenOrders(): ContractRoute {
        return "orders" meta {
            operationId = "cancel-open-orders"
            summary = "Cancel open orders"
        } bindContract Method.DELETE to { _ ->
            transaction {
                // TODO filter by current user
                OrderEntity.cancelAll()
            }
            Response(Status.NO_CONTENT)
        }
    }

    fun batchOrders(): ContractRoute {
        val requestBody = Body.auto<BatchOrdersApiRequest>().toLens()
        val responseBody = Body.auto<OrdersApiResponse>().toLens()

        return "batch/orders" meta {
            operationId = "batch-orders"
            summary = "Manage orders in batch"
        } bindContract Method.POST to { request ->
            val apiRequest: BatchOrdersApiRequest = requestBody(request)

            logger.debug {
                Json.encodeToString(apiRequest)
            }

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
            queries += Query.string().optional("before-timestamp", "Return trades executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
        } bindContract Method.GET to { request ->
            val timestamp = request.query("before-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            Response(Status.OK).with(
                responseBody of TradesApiResponse(
                    emptyList(),
                ),
            )
        }
    }
}
