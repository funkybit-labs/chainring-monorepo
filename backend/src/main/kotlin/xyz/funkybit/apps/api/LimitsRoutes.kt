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
import xyz.funkybit.apps.api.model.GetLimitsApiResponse
import xyz.funkybit.apps.api.model.MarketLimits
import xyz.funkybit.core.model.db.LimitEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal

object LimitsRoutes {
    private val logger = KotlinLogging.logger {}

    val getLimits: ContractRoute = run {
        val responseBody = Body.auto<GetLimitsApiResponse>().toLens()

        "limits" meta {
            operationId = "get-limits"
            summary = "Get limits"
            security = signedTokenSecurity
            tags += listOf(Tag("limits"))
            returning(
                Status.OK,
                responseBody to GetLimitsApiResponse(
                    listOf(
                        MarketLimits(
                            MarketId("BTC/ETH"),
                            base = BigDecimal("1").toFundamentalUnits(18),
                            quote = BigDecimal("2").toFundamentalUnits(18),
                        ),
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                val wallet = WalletEntity.getOrCreate(request.principal)
                Response(Status.OK).with(
                    responseBody of GetLimitsApiResponse(LimitEntity.forWallet(wallet)),
                )
            }
        }
    }
}
