package co.chainring.apps.api

import co.chainring.apps.api.model.BalancesApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto

object BalanceRoutes {
    private val logger = KotlinLogging.logger {}

    fun balances(): ContractRoute {
        val responseBody = Body.auto<BalancesApiResponse>().toLens()

        return "balances" meta {
            operationId = "get-balances"
            summary = "Get order"
            returning(
                Status.OK,
                responseBody to BalancesApiResponse(
                    listOf(Examples.BTCBalance, Examples.ETHBalance),
                ),
            )
        } bindContract Method.GET to { _ ->
            Response(Status.OK).with(
                responseBody of BalancesApiResponse(
                    listOf(Examples.BTCBalance, Examples.ETHBalance),
                ),
            )
        }
    }
}
