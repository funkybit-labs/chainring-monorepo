package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BalancesApiResponse
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.WalletEntity
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

class BalanceRoutes(private val blockchainClient: BlockchainClient) {
    private val logger = KotlinLogging.logger {}

    fun getBalances(): ContractRoute {
        val responseBody = Body.auto<BalancesApiResponse>().toLens()

        return "balances" meta {
            operationId = "get-balances"
            summary = "Get balances"
            security = signedTokenSecurity
            tags += listOf(Tag("balances"))
            returning(
                Status.OK,
                responseBody to BalancesApiResponse(
                    listOf(Examples.USDCBalance, Examples.ETHBalance),
                ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                val (availableBalances, exchangeBalances) = BalanceEntity.getBalancesForWallet(
                    WalletEntity.getOrCreate(request.principal),
                ).partition { it.type == BalanceType.Available }
                val exchangeBalanceMap = exchangeBalances.associate { it.symbolGuid.value to it.balance }

                Response(Status.OK).with(
                    responseBody of BalancesApiResponse(
                        availableBalances.map { availableBalance ->
                            Balance(
                                Symbol(availableBalance.symbol.name),
                                exchangeBalanceMap.getOrDefault(availableBalance.symbol.guid.value, BigInteger.ZERO),
                                availableBalance.balance,
                                availableBalance.updatedAt ?: availableBalance.createdAt,
                            )
                        },
                    ),
                )
            }
        }
    }
}
