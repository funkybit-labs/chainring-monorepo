package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.GetLimitsApiResponse
import co.chainring.apps.api.model.MarketLimits
import co.chainring.core.model.db.LimitEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.toFundamentalUnits
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
