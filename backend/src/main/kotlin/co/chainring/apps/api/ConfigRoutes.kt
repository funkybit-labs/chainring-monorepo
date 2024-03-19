package co.chainring.apps.api

import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.ERC20Token
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.Chain
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.ERC20TokenEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction

object ConfigRoutes {
    private val logger = KotlinLogging.logger {}

    fun getConfiguration(): ContractRoute {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        return "config" meta {
            operationId = "config"
            summary = "Retrive configuration"
            returning(
                Status.OK,
                responseBody to
                    ConfigurationApiResponse(
                        contracts = listOf(
                            DeployedContract(
                                chain = Chain.Ethereum,
                                name = "Exchange",
                                address = Address("0x0000000000000000000000000000000000000000"),
                            ),
                        ),
                        erc20Tokens = listOf(
                            ERC20Token(
                                chain = Chain.Ethereum,
                                name = "USD Coin",
                                symbol = Symbol("USDC"),
                                address = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                            ),
                        ),
                    ),
            )
        } bindContract Method.GET to { _ ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        ConfigurationApiResponse(
                            contracts = DeployedSmartContractEntity.validContracts().map {
                                DeployedContract(
                                    chain = it.chain,
                                    name = it.name,
                                    address = it.proxyAddress ?: it.implementationAddress,
                                )
                            },
                            erc20Tokens = ERC20TokenEntity.all().map {
                                ERC20Token(
                                    chain = it.chain,
                                    name = it.name,
                                    symbol = it.symbol,
                                    address = it.address,
                                )
                            },
                        ),
                )
            }
        }
    }
}
