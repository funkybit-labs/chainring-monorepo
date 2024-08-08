package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.BalancesApiResponse
import xyz.funkybit.core.model.db.BalanceEntity.Companion.balancesAsApiResponse
import xyz.funkybit.core.model.db.WalletEntity

class BalanceRoutes {
    private val logger = KotlinLogging.logger {}

    fun getBalances(): ContractRoute {
        val responseBody = Body.auto<BalancesApiResponse>().toLens()

        return "balances" meta {
            operationId = "get-balances"
            summary = "Get balances"
            security = signedTokenSecurity
            tags += listOf(Tag("balances"))
            returning(
                Status.OK,
                responseBody to BalancesApiResponse(
                    listOf(Examples.USDCBalance, Examples.ETHBalance),
                ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of balancesAsApiResponse(WalletEntity.getOrCreate(request.principal)),
                )
            }
        }
    }
}
