package co.chainring.apps.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.apps.api.model.FaucetApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.apps.api.model.errorResponse
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
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

class FaucetRoutes(private val faucetMode: FaucetMode, blockchainClients: Collection<BlockchainClient>) {
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
                    address = Address.zero,
                ),
            )
            returning(
                Status.OK,
                responseBody to FaucetApiResponse(
                    chainId = ChainId.empty,
                    txHash = TxHash.emptyHash(),
                    symbol = Symbol("USDC"),
                    amount = BigDecimal("0.1").toFundamentalUnits(18),
                ),
            )
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)
            val symbol = transaction { SymbolEntity.forName(payload.symbol) }

            when (val blockchainClient = blockchainClients.firstOrNull { it.chainId == symbol.chainId.value }) {
                null -> errorResponse(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ChainNotSupported, "Chain not supported"))
                else -> {
                    val amount = BigDecimal("1")

                    logger.debug { "Sending $amount ${symbol.name} to ${payload.address.value}" }

                    val amountInFundamentalUnits = amount.movePointRight(symbol.decimals.toInt()).toBigInteger()
                    val txHash = if (symbol.faucetSupported(faucetMode)) {
                        when (val tokenContractAddress = symbol.contractAddress) {
                            null -> blockchainClient.asyncDepositNative(payload.address, amountInFundamentalUnits)
                            else -> blockchainClient.asyncMintERC20(tokenContractAddress, payload.address, amountInFundamentalUnits)
                        }
                    } else {
                        throw RequestProcessingError(Status.UNPROCESSABLE_ENTITY, ApiError(ReasonCode.ProcessingError, "Native faucet is disabled"))
                    }

                    Response(Status.OK).with(
                        responseBody of FaucetApiResponse(
                            chainId = blockchainClient.chainId,
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
