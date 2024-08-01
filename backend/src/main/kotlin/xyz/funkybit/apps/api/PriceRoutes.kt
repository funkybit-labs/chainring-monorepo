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
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.GetLastPriceResponse
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.notFoundError
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId

object PriceRoutes {
    private val logger = KotlinLogging.logger {}

    val getLastPriceForMarket: ContractRoute = run {
        val marketIdPathParam = Path.map(::MarketId, MarketId::value).of("marketId", "Market Id")
        val responseBody = Body.auto<GetLastPriceResponse>().toLens()

        "last-price" / marketIdPathParam meta {
            operationId = "config"
            summary = "Get last price for market"
            tags += listOf(Tag("price"))
            returning(
                Status.OK,
                responseBody to
                    GetLastPriceResponse(
                        lastPrice = "0.995".toBigDecimal(),
                    ),
            )
        } bindContract Method.GET to { marketId ->
            { _: Request ->
                transaction {
                    MarketEntity.findById(marketId)?.let { market ->
                        Response(Status.OK).with(
                            responseBody of GetLastPriceResponse(market.lastPrice),
                        )
                    } ?: notFoundError(ReasonCode.MarketNotFound, "Unknown market")
                }
            }
        }
    }
}
