package co.chainring.apps.api

import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.Withdrawal
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.apps.api.model.badRequestError
import co.chainring.apps.api.model.notFoundError
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import io.github.oshai.kotlinlogging.KotlinLogging
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
import org.jetbrains.exposed.sql.transactions.transaction

class WithdrawalRoutes(private val blockchainClient: BlockchainClient) {
    private val logger = KotlinLogging.logger {}

    private val withdrawalIdPathParam = Path.map(::WithdrawalId, WithdrawalId::value).of("withdrawalId", "Withdrawal Id")

    fun createWithdrawal(): ContractRoute {
        val requestBody = Body.auto<CreateWithdrawalApiRequest>().toLens()
        val responseBody = Body.auto<WithdrawalApiResponse>().toLens()

        return "withdrawals" meta {
            operationId = "withdraw"
            summary = "Withdraw"
            returning(
                Status.CREATED,
                responseBody to WithdrawalApiResponse(
                    Examples.withdrawal,
                ),
            )
        } bindContract Method.POST to { request ->
            val withdrawTx = requestBody(request).toEip712Transaction()

            val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
            val isSignatureValid = contractAddress?.let { verifyingContract ->
                EIP712Helper.isValidSignature(
                    EIP712Helper.computeHash(
                        withdrawTx,
                        blockchainClient.chainId,
                        verifyingContract,
                    ),
                    withdrawTx.signature,
                    withdrawTx.sender,
                )
            } ?: false

            when {
                contractAddress == null -> badRequestError(ReasonCode.ContractUpdating, "Contract is updating")

                !isSignatureValid -> badRequestError(ReasonCode.SignatureNotValid, "Signature not verified")

                else -> {
                    val entity = transaction {
                        WithdrawalEntity.create(
                            withdrawTx.nonce,
                            blockchainClient.chainId,
                            withdrawTx.sender,
                            withdrawTx.token,
                            withdrawTx.amount,
                            withdrawTx.signature,
                        )
                    }
                    blockchainClient.queueTransaction(withdrawTx)
                    Response(Status.CREATED).with(
                        responseBody of WithdrawalApiResponse(
                            transaction {
                                Withdrawal.fromEntity(WithdrawalEntity[entity.id])
                            },
                        ),
                    )
                }
            }
        }
    }

    fun getWithdrawal(): ContractRoute {
        val responseBody = Body.auto<WithdrawalApiResponse>().toLens()

        return "withdrawals" / withdrawalIdPathParam meta {
            operationId = "get-withdrawal"
            summary = "Get withdrawal"
            returning(
                Status.OK,
                responseBody to WithdrawalApiResponse(
                    Examples.withdrawal,
                ),
            )
        } bindContract Method.GET to { withdrawalId ->
            { _: Request ->
                transaction {
                    when (val withdrawalEntity = WithdrawalEntity.findById(withdrawalId)) {
                        null -> notFoundError(ReasonCode.WithdrawalNotFound, "Withdrawal Not Found")
                        else -> {
                            Response(Status.OK).with(
                                responseBody of WithdrawalApiResponse(Withdrawal.fromEntity(withdrawalEntity)),
                            )
                        }
                    }
                }
            }
        }
    }
}
