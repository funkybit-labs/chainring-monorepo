package co.chainring.apps.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.errorResponse
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.db.SymbolEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal

class FaucetRoutes(blockchainClients: Collection<BlockchainClient>) {
    private val logger = KotlinLogging.logger { }

    private val faucet: ContractRoute = run {
        val requestBody = Body.auto<FaucetApiRequest>().toLens()

        "faucet" meta {
            operationId = "faucet"
            summary = "Faucet"
            tags += listOf(Tag("faucet"))
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)

            when (val blockchainClient = blockchainClients.firstOrNull { it.chainId == payload.chainId }) {
                null -> errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ChainNotSupported, "Chain not supported"))
                else -> {
                    val nativeSymbol = transaction { SymbolEntity.forChainAndContractAddress(chainId = payload.chainId, contractAddress = null) }
                    logger.debug { "Sending 0.1 ${nativeSymbol.name} on chain ${nativeSymbol.chainId.value} to ${payload.address.value}" }
                    blockchainClient.asyncDepositNative(payload.address, BigDecimal("0.1").movePointRight(nativeSymbol.decimals.toInt()).toBigInteger())
                    Response(Status.NO_CONTENT)
                }
            }
        }
    }

    val routes = listOf(faucet)
}
