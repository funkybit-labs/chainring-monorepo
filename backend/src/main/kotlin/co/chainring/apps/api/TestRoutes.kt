package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.CreateSequencerDeposit
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.services.ExchangeService
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
import java.math.BigInteger

class TestRoutes(private val exchangeService: ExchangeService) {
    private val logger = KotlinLogging.logger { }

    fun createSequencerDeposit(): ContractRoute {
        val requestBody = Body.auto<CreateSequencerDeposit>().toLens()
        val responseBody = Body.auto<CreateSequencerDeposit>().toLens()

        return "sequencer-deposits" meta {
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
}
