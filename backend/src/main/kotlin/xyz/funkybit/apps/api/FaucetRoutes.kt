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
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.FaucetApiRequest
import xyz.funkybit.apps.api.model.FaucetApiResponse
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.errorResponse
import xyz.funkybit.core.blockchain.Faucet
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.FaucetDripEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal

class FaucetRoutes(private val faucetMode: FaucetMode) {
    private val logger = KotlinLogging.logger { }

    val faucet: ContractRoute = run {
        val requestBody = Body.auto<FaucetApiRequest>().toLens()
        val responseBody = Body.auto<FaucetApiResponse>().toLens()

        "faucet" meta {
            operationId = "faucet"
            summary = "Faucet"
            tags += listOf(Tag("faucet"))
            receiving(
                requestBody to FaucetApiRequest(
                    symbol = Symbol("USDC"),
                    address = EvmAddress.zero,
                ),
            )
            returning(
                Status.OK,
                responseBody to FaucetApiResponse(
                    chainId = ChainId.empty,
                    txHash = TxHash.emptyHash,
                    symbol = Symbol("USDC"),
                    amount = BigDecimal("0.1").toFundamentalUnits(18),
                ),
            )
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)
            val sourceAddress = request.header("X-Forwarded-For") ?: request.source?.address
            if (sourceAddress == null) {
                errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ProcessingError, "Source address not found"))
            } else {
                val walletAddress = payload.address
                val(symbol, eligible) = transaction {
                    SymbolEntity.forName(payload.symbol).let {
                        Pair(it, FaucetDripEntity.eligible(it, walletAddress, sourceAddress))
                    }
                }

                if (!eligible) {
                    errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ProcessingError, "Faucet may only be used once per day."))
                } else {
                    val (amountInFundamentalUnits, txHash) = if (symbol.faucetSupported(faucetMode)) {
                        Faucet.transfer(symbol, payload.address)
                    } else {
                        throw RequestProcessingError(
                            Status.UNPROCESSABLE_ENTITY,
                            ApiError(ReasonCode.ProcessingError, "Faucet is disabled"),
                        )
                    }

                    transaction {
                        FaucetDripEntity.create(symbol, walletAddress, sourceAddress)
                    }

                    Response(Status.OK).with(
                        responseBody of FaucetApiResponse(
                            chainId = symbol.chainId.value,
                            txHash = txHash,
                            symbol = payload.symbol,
                            amount = amountInFundamentalUnits,
                        ),
                    )
                }
            }
        }
    }
}
