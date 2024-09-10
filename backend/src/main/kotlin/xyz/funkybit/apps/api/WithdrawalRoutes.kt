package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.ListWithdrawalsApiResponse
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.apps.api.model.WithdrawalApiResponse
import xyz.funkybit.apps.api.model.notFoundError
import xyz.funkybit.apps.api.services.ExchangeApiService
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
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
            val wallet = request.principal
            Response(Status.CREATED).with(
                responseBody of exchangeApiService.withdraw(wallet, requestBody(request)),
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
            { request ->
                transaction {
                    WithdrawalEntity.findByIdForUser(withdrawalId, request.principal.userGuid)?.let {
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
                        WithdrawalEntity.history(request.principal.userGuid).map(Withdrawal::fromEntity),
                    ),
                )
            }
        }
    }
}
