package co.chainring.apps.api

import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.GetOrderBookApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.notFoundError
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderBookSnapshot
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
import org.jetbrains.exposed.sql.transactions.transaction

object OrderBookRoutes {
    private val marketIdPathParam = Path.map(::MarketId, MarketId::value).of("marketId", "Market Id")

    val getOrderBook: ContractRoute = run {
        val responseBody = Body.auto<GetOrderBookApiResponse>().toLens()

        "order-book" / marketIdPathParam meta {
            operationId = "get-order-book"
            summary = "Get order book"
            security = signedTokenSecurity
            tags += listOf(Tag("order-book"))
            returning(
                Status.OK,
                responseBody to Examples.getOrderBookApiResponse,
            )
        } bindContract Method.GET to { marketId ->
            { _: Request ->
                transaction {
                    MarketEntity.findById(marketId)?.let { market ->
                        Response(Status.OK).with(
                            responseBody of GetOrderBookApiResponse(market, OrderBookSnapshot.get(market)),
                        )
                    } ?: notFoundError(ReasonCode.MarketNotFound, "Unknown market")
                }
            }
        }
    }
}
