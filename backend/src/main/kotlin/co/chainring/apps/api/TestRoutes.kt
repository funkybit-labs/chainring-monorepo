package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.services.ExchangeService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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
import java.math.BigInteger

class TestRoutes(
    private val exchangeService: ExchangeService,
    private val sequencerClient: SequencerClient,
) {
    private val logger = KotlinLogging.logger { }

    companion object {
        @Serializable
        data class CreateMarketInSequencer(
            val id: String,
            val tickSize: BigDecimalJson,
            val marketPrice: BigDecimalJson,
            val baseDecimals: Int,
            val quoteDecimals: Int,
        )

        @Serializable
        data class CreateSequencerDeposit(
            val symbol: String,
            val amount: BigIntegerJson,
        )
    }

    private val resetSequencer: ContractRoute =
        "sequencer" meta {
            operationId = "reset-sequencer"
            summary = "Reset Sequencer"
            tags += listOf(Tag("test"))
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { _ ->
            ApiUtils.runCatchingValidation {
                runBlocking {
                    sequencerClient.reset()
                }
                Response(Status.NO_CONTENT)
            }
        }

    private val createMarketInSequencer: ContractRoute = run {
        val requestBody = Body.auto<CreateMarketInSequencer>().toLens()

        "sequencer-markets" meta {
            operationId = "sequencer-markets"
            summary = "Create Market in Sequencer"
            tags += listOf(Tag("test"))
            receiving(
                requestBody to CreateMarketInSequencer(
                    id = "BTC/ETH",
                    tickSize = "0.05".toBigDecimal(),
                    marketPrice = "17.55".toBigDecimal(),
                    baseDecimals = 18,
                    quoteDecimals = 18,
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.POST to { request ->
            ApiUtils.runCatchingValidation {
                val payload = requestBody(request)
                runBlocking {
                    sequencerClient.createMarket(
                        marketId = payload.id,
                        tickSize = payload.tickSize,
                        marketPrice = payload.marketPrice,
                        baseDecimals = payload.baseDecimals,
                        quoteDecimals = payload.quoteDecimals,
                    )
                }
                Response(Status.CREATED)
            }
        }
    }

    private val createSequencerDeposit: ContractRoute = run {
        val requestBody = Body.auto<CreateSequencerDeposit>().toLens()
        val responseBody = Body.auto<CreateSequencerDeposit>().toLens()

        "sequencer-deposits" meta {
            operationId = "sequencer-deposits"
            summary = "Sequencer Deposit"
            security = signedTokenSecurity
            tags += listOf(Tag("test"))
            receiving(
                requestBody to CreateSequencerDeposit(
                    "BTC",
                    "12345".toBigInteger(),
                ),
            )
            returning(
                Status.CREATED,
                responseBody to CreateSequencerDeposit("USDC", BigInteger("1234")),
            )
        } bindContract Method.POST to { request ->
            ApiUtils.runCatchingValidation {
                val sequencerDeposit = requestBody(request)
                exchangeService.deposit(
                    transaction { WalletEntity.getOrCreate(request.principal) },
                    sequencerDeposit.symbol,
                    sequencerDeposit.amount,
                )
                Response(Status.CREATED).with(
                    responseBody of sequencerDeposit,
                )
            }
        }
    }

    val routes = listOf(
        createMarketInSequencer,
        createSequencerDeposit,
        resetSequencer,
    )
}
