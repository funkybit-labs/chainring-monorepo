package co.chainring.apps.api

import co.chainring.apps.api.model.Chain
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.model.Address
import co.chainring.core.model.FeeRate
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainTable
import co.chainring.core.model.db.DeployedSmartContractEntity
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.SymbolEntity
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction

object ConfigRoutes {
    private val logger = KotlinLogging.logger {}

    fun getConfiguration(): ContractRoute {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        return "config" meta {
            operationId = "config"
            summary = "Get configuration"
            tags += listOf(Tag("configuration"))
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
                                symbols = listOf(
                                    SymbolInfo(
                                        name = "ETH",
                                        description = "Ethereum",
                                        contractAddress = null,
                                        decimals = 18u,
                                    ),
                                    SymbolInfo(
                                        name = "USDC",
                                        description = "USD Coin",
                                        contractAddress = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                        decimals = 18u,
                                    ),
                                ),
                            ),
                        ),
                        markets = listOf(
                            Market(
                                id = "USDC/DAI",
                                baseSymbol = Symbol("USDC"),
                                baseDecimals = 18,
                                quoteSymbol = Symbol("DAI"),
                                quoteDecimals = 6,
                                tickSize = "0.01".toBigDecimal(),
                            ),
                        ),
                        feeRates = FeeRates(
                            maker = FeeRate.fromPercents(1.0),
                            taker = FeeRate.fromPercents(2.0),
                        ),
                    ),
            )
        } bindContract Method.GET to { _ ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        ConfigurationApiResponse(
                            chains = ChainEntity.all().orderBy(ChainTable.id to SortOrder.ASC).map { chain ->
                                Chain(
                                    id = chain.id.value,
                                    contracts = DeployedSmartContractEntity.validContracts(chain.id.value).map {
                                        DeployedContract(
                                            name = it.name,
                                            address = it.proxyAddress,
                                        )
                                    },
                                    symbols = SymbolEntity.forChain(chain.id.value).map {
                                        SymbolInfo(
                                            name = it.name,
                                            description = it.description,
                                            contractAddress = it.contractAddress,
                                            decimals = it.decimals,
                                        )
                                    },
                                )
                            },
                            markets = MarketEntity.all().map { market ->
                                Market(
                                    id = market.id.value.toString(),
                                    baseSymbol = Symbol(market.baseSymbol.name),
                                    baseDecimals = market.baseSymbol.decimals.toInt(),
                                    quoteSymbol = Symbol(market.quoteSymbol.name),
                                    quoteDecimals = market.quoteSymbol.decimals.toInt(),
                                    tickSize = market.tickSize,
                                )
                            }.sortedWith(compareBy({ it.baseSymbol.value }, { it.quoteSymbol.value })),
                            feeRates = FeeRates.fetch(),
                        ),
                )
            }
        }
    }
}
