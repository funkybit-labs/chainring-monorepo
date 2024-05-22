package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.DepositApiResponse
import co.chainring.apps.api.model.ListDepositsApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.notFoundError
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositId
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
import java.math.BigInteger

class DepositRoutes(val exchangeApiService: ExchangeApiService) {
    private val logger = KotlinLogging.logger {}

    private val depositIdPathParam = Path.map(::DepositId, DepositId::value).of("depositId", "Deposit Id")

    val createDeposit: ContractRoute = run {
        val requestBody = Body.auto<CreateDepositApiRequest>().toLens()
        val responseBody = Body.auto<DepositApiResponse>().toLens()

        "deposits" meta {
            operationId = "deposit"
            summary = "Deposit"
            security = signedTokenSecurity
            tags += listOf(Tag("deposit"))
            receiving(
                requestBody to CreateDepositApiRequest(
                    symbol = Symbol("USDC"),
                    amount = BigInteger.valueOf(1000),
                    txHash = TxHash.emptyHash(),
                ),
            )
            returning(
                Status.CREATED,
                responseBody to DepositApiResponse(
                    Examples.deposit,
                ),
            )
        } bindContract Method.POST to { request ->
            Response(Status.CREATED).with(
                responseBody of exchangeApiService.deposit(request.principal, requestBody(request)),
            )
        }
    }

    val getDeposit: ContractRoute = run {
        val responseBody = Body.auto<DepositApiResponse>().toLens()

        "deposits" / depositIdPathParam meta {
            operationId = "get-deposit"
            summary = "Get deposit"
            security = signedTokenSecurity
            tags += listOf(Tag("deposit"))
            returning(
                Status.OK,
                responseBody to DepositApiResponse(
                    Examples.deposit,
                ),
            )
        } bindContract Method.GET to { id ->
            { _: Request ->
                transaction {
                    DepositEntity.findById(id)?.let {
                        Response(Status.OK).with(
                            responseBody of DepositApiResponse(Deposit.fromEntity(it)),
                        )
                    } ?: notFoundError(ReasonCode.DepositNotFound, "Deposit Not Found")
                }
            }
        }
    }

    val listDeposits: ContractRoute = run {
        val responseBody = Body.auto<ListDepositsApiResponse>().toLens()

        "deposits" meta {
            operationId = "list-deposits"
            summary = "List deposits"
            security = signedTokenSecurity
            tags += listOf(Tag("deposit"))
            returning(
                Status.OK,
                responseBody to ListDepositsApiResponse(
                    listOf(
                        Examples.deposit,
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of ListDepositsApiResponse(
                        DepositEntity.history(request.principal).map(Deposit::fromEntity),
                    ),
                )
            }
        }
    }
}
