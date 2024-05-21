package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.ListWithdrawalsApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.Withdrawal
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.apps.api.model.notFoundError
import co.chainring.apps.api.services.ExchangeApiService
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
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

class WithdrawalRoutes(private val exchangeApiService: ExchangeApiService) {
    private val logger = KotlinLogging.logger {}

    private val withdrawalIdPathParam = Path.map(::WithdrawalId, WithdrawalId::value).of("withdrawalId", "Withdrawal Id")

    val createWithdrawal: ContractRoute = run {
        val requestBody = Body.auto<CreateWithdrawalApiRequest>().toLens()
        val responseBody = Body.auto<WithdrawalApiResponse>().toLens()

        "withdrawals" meta {
            operationId = "withdraw"
            summary = "Withdraw"
            security = signedTokenSecurity
            tags += listOf(Tag("withdrawal"))
            receiving(
                requestBody to CreateWithdrawalApiRequest(
                    symbol = Symbol("USDC"),
                    amount = BigInteger.valueOf(1000),
                    nonce = 1,
                    signature = EvmSignature.emptySignature(),
                ),
            )
            returning(
                Status.CREATED,
                responseBody to WithdrawalApiResponse(
                    Examples.withdrawal,
                ),
            )
        } bindContract Method.POST to { request ->
            Response(Status.CREATED).with(
                responseBody of exchangeApiService.withdraw(request.principal, requestBody(request)),
            )
        }
    }

    val getWithdrawal: ContractRoute = run {
        val responseBody = Body.auto<WithdrawalApiResponse>().toLens()

        "withdrawals" / withdrawalIdPathParam meta {
            operationId = "get-withdrawal"
            summary = "Get withdrawal"
            security = signedTokenSecurity
            tags += listOf(Tag("withdrawal"))
            returning(
                Status.OK,
                responseBody to WithdrawalApiResponse(
                    Examples.withdrawal,
                ),
            )
        } bindContract Method.GET to { withdrawalId ->
            { _: Request ->
                transaction {
                    WithdrawalEntity.findById(withdrawalId)?.let {
                        Response(Status.OK).with(
                            responseBody of WithdrawalApiResponse(Withdrawal.fromEntity(it)),
                        )
                    } ?: notFoundError(ReasonCode.WithdrawalNotFound, "Withdrawal Not Found")
                }
            }
        }
    }

    val listWithdrawals: ContractRoute = run {
        val responseBody = Body.auto<ListWithdrawalsApiResponse>().toLens()

        "withdrawals" meta {
            operationId = "list-withdrawals"
            summary = "List withdrawals"
            security = signedTokenSecurity
            tags += listOf(Tag("withdrawal"))
            returning(
                Status.OK,
                responseBody to ListWithdrawalsApiResponse(
                    listOf(
                        Examples.withdrawal,
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of ListWithdrawalsApiResponse(
                        WithdrawalEntity.history(request.principal).map(Withdrawal::fromEntity),
                    ),
                )
            }
        }
    }
}
