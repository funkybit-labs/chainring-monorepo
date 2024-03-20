package co.chainring.apps.api

import co.chainring.apps.api.model.Chain
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.ERC20Token
import co.chainring.apps.api.model.NativeToken
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.ChainId
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
            summary = "Get configuration"
            returning(
                Status.OK,
                responseBody to
                    ConfigurationApiResponse(
                        chains = listOf(
                            Chain(
                                id = ChainId(1337u),
                                contracts = listOf(
                                    DeployedContract(
                                        name = "Exchange",
                                        address = Address("0x0000000000000000000000000000000000000000"),
                                    ),
                                ),
                                erc20Tokens = listOf(
                                    ERC20Token(
                                        name = "USD Coin",
                                        symbol = Symbol("USDC"),
                                        address = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                        decimals = 18u,
                                    ),
                                ),
                                nativeToken = NativeToken(
                                    name = "Ethereum",
                                    symbol = Symbol("ETH"),
                                    decimals = 18u,
                                ),
                            ),
                        ),
                    ),
            )
        } bindContract Method.GET to { _ ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        ConfigurationApiResponse(
                            chains = ChainEntity.all().map { chain ->
                                Chain(
                                    id = chain.id.value,
                                    contracts = DeployedSmartContractEntity.validContracts(chain.id.value).map {
                                        DeployedContract(
                                            name = it.name,
                                            address = it.proxyAddress ?: it.implementationAddress,
                                        )
                                    },
                                    erc20Tokens = ERC20TokenEntity.forChain(chain.id.value).map {
                                        ERC20Token(
                                            name = it.name,
                                            symbol = it.symbol,
                                            address = it.address,
                                            decimals = 18u,
                                        )
                                    },
                                    nativeToken = NativeToken(
                                        name = chain.nativeTokenName,
                                        symbol = chain.nativeTokenSymbol,
                                        decimals = chain.nativeTokenDecimals,
                                    ),
                                )
                            },
                        ),
                )
            }
        }
    }
}
