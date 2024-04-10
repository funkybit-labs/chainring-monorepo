package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.CreateSequencerDeposit
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.services.ExchangeService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger

class TestRoutes(private val exchangeService: ExchangeService) {
    private val logger = KotlinLogging.logger { }
    private var needToSeedSequencerMarkets = true

    fun createSequencerDeposit(): ContractRoute {
        val requestBody = Body.auto<CreateSequencerDeposit>().toLens()
        val responseBody = Body.auto<CreateSequencerDeposit>().toLens()

        return "sequencer-deposits" meta {
            operationId = "sequencer-deposits"
            summary = "Sequencer Deposit"
            security = signedTokenSecurity
            returning(
                Status.CREATED,
                responseBody to CreateSequencerDeposit("USDC", BigInteger("1234")),
            )
        } bindContract Method.POST to { request ->
            seedSequencerMarkets()
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

    private fun seedSequencerMarkets() {
        if (needToSeedSequencerMarkets) {
            transaction {
                MarketEntity.all().forEach {
                    runBlocking {
                        val marketPrice = if (it.guid.value.value == "BTC/ETH") "17.525" else "2.05"
                        SequencerClient.createMarket(
                            it.guid.value.value,
                            tickSize = it.tickSize,
                            marketPrice = marketPrice.toBigDecimal(),
                            quoteDecimals = it.quoteSymbol.decimals.toInt(),
                            baseDecimals = it.baseSymbol.decimals.toInt(),
                        )
                    }
                }
            }
            needToSeedSequencerMarkets = false
        }
    }
}
