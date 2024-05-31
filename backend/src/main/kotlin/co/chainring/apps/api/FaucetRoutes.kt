package co.chainring.apps.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.apps.api.model.FaucetApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.errorResponse
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.SymbolEntity
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

class FaucetRoutes(blockchainClients: Collection<BlockchainClient>) {
    private val logger = KotlinLogging.logger { }

    private val faucet: ContractRoute = run {
        val requestBody = Body.auto<FaucetApiRequest>().toLens()
        val responseBody = Body.auto<FaucetApiResponse>().toLens()

        "faucet" meta {
            operationId = "faucet"
            summary = "Faucet"
            tags += listOf(Tag("faucet"))
            receiving(
                requestBody to FaucetApiRequest(
                    chainId = ChainId.empty,
                    address = Address.zero,
                ),
            )
            returning(
                Status.OK,
                responseBody to FaucetApiResponse(
                    chainId = ChainId.empty,
                    txHash = TxHash.emptyHash(),
                    amount = BigDecimal("0.1").toFundamentalUnits(18),
                ),
            )
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)

            when (val blockchainClient = blockchainClients.firstOrNull { it.chainId == payload.chainId }) {
                null -> errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ChainNotSupported, "Chain not supported"))
                else -> {
                    val nativeSymbol = transaction { SymbolEntity.forChainAndContractAddress(chainId = payload.chainId, contractAddress = null) }
                    val amount = BigDecimal("0.1")

                    logger.debug { "Sending $amount ${nativeSymbol.name} on chain ${nativeSymbol.chainId.value} to ${payload.address.value}" }

                    val amountInFundamentalUnits = amount.movePointRight(nativeSymbol.decimals.toInt()).toBigInteger()
                    val txHash = blockchainClient.asyncDepositNative(payload.address, amountInFundamentalUnits)

                    Response(Status.OK).with(
                        responseBody of FaucetApiResponse(
                            chainId = blockchainClient.chainId,
                            txHash = txHash,
                            amount = amountInFundamentalUnits,
                        ),
                    )
                }
            }
        }
    }

    val routes = listOf(faucet)
}
