package co.chainring.apps.api

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.core.model.OrderId
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

object OrderRoutes {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderOd", "Order Id")

    fun createOrder(): ContractRoute {
        val requestBody = Body.auto<CreateOrderApiRequest>().toLens()
        val responseBody = Body.auto<OrderApiResponse>().toLens()

        return "orders" meta {
            operationId = "create-order"
            summary = "Create order"
            returning(
                Status.CREATED,
                responseBody to Examples.marketOrderResponse,
            )
        } bindContract Method.POST to { request ->
            val apiRequest: CreateOrderApiRequest = requestBody(request)

            logger.debug {
                Json.encodeToString(apiRequest)
            }

            Response(Status.CREATED).with(
                responseBody of Examples.marketOrderResponse,
            )
        }
    }

    fun updateOrder(): ContractRoute {
        val requestBody = Body.auto<UpdateOrderApiRequest>().toLens()
        val responseBody = Body.auto<OrderApiResponse>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "update-order"
            summary = "Update order"
            returning(
                Status.OK,
                responseBody to Examples.marketOrderResponse,
            )
        } bindContract Method.PATCH to { orderId ->
            fun handle(request: Request): Response {
                val apiRequest: UpdateOrderApiRequest = requestBody(request)

                logger.debug { orderId }
                logger.debug { Json.encodeToString(apiRequest) }

                return Response(Status.OK).with(
                    responseBody of Examples.marketOrderResponse,
                )
            }
            ::handle
        }
    }

    fun deleteOrder(): ContractRoute {
        return "orders" / orderIdPathParam meta {
            operationId = "delete-order"
            summary = "Delete order"
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { orderId ->
            fun handle(request: Request): Response {
                logger.debug { orderId }
                return Response(Status.NO_CONTENT)
            }
            ::handle
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
        } bindContract Method.GET to { orderId ->
            fun handle(request: Request): Response {
                logger.debug { orderId }
                return Response(Status.OK).with(
                    responseBody of Examples.marketOrderResponse,
                )
            }
            ::handle
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
                    orders = listOf(Examples.marketOrderResponse),
                ),
            )
        } bindContract Method.GET to { _ ->
            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    listOf(Examples.marketOrderResponse),
                ),
            )
        }
    }

    fun cancelOpenOrders(): ContractRoute {
        return "orders" meta {
            operationId = "delete-all-orders"
            summary = "Delete all open orders"
        } bindContract Method.DELETE to { _ ->
            Response(Status.OK)
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
            queries += Query.string().optional("since-timestamp", "Return trader executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
        } bindContract Method.GET to { request ->
            val timestamp = request.query("since-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            Response(Status.OK).with(
                responseBody of TradesApiResponse(
                    emptyList(),
                ),
            )
        }
    }
}
