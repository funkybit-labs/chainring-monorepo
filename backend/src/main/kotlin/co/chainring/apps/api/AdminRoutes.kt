package co.chainring.apps.api

import co.chainring.apps.api.middleware.adminSecurity
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.utils.fromFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.contract.security.and
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.string
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigInteger

class AdminRoutes(
    private val sequencerClient: SequencerClient,
) {
    private val logger = KotlinLogging.logger { }

    companion object {
        @Serializable
        data class AdminSymbol(
            val chainId: ChainId,
            val name: String,
            val description: String,
            val contractAddress: Address?,
            val decimals: UByte,
            val iconUrl: String,
            val withdrawalFee: BigIntegerJson,
            val addToWallets: Boolean,
        )

        @Serializable
        data class AdminMarket(
            val id: MarketId,
            val tickSize: BigDecimalJson,
            val lastPrice: BigDecimalJson,
            val minFee: BigIntegerJson,
        )
    }

    private val createSymbol: ContractRoute = run {
        val requestBody = Body.auto<AdminSymbol>().toLens()
        "admin/symbol" meta {
            operationId = "create-symbol"
            summary = "Create Symbol"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            receiving(
                requestBody to AdminSymbol(
                    chainId = ChainId(31337u),
                    name = "NAME",
                    description = "Description",
                    contractAddress = null,
                    decimals = 18u,
                    iconUrl = "icon.svg",
                    withdrawalFee = BigInteger.valueOf(100),
                    addToWallets = false,
                ),
            )
            returning(Status.CREATED)
        } bindContract Method.POST to { request ->
            transaction {
                runBlocking {
                    val payload = requestBody(request)
                    SymbolEntity.create(
                        chainId = payload.chainId,
                        name = payload.name.replace(Regex(":.*$"), ""),
                        contractAddress = payload.contractAddress,
                        decimals = payload.decimals,
                        addToWallets = payload.addToWallets,
                        withdrawalFee = payload.withdrawalFee,
                        description = payload.description,
                        iconUrl = payload.iconUrl,
                    )
                }
            }
            Response(Status.CREATED)
        }
    }

    private val listSymbols: ContractRoute = run {
        val responseBody = Body.auto<List<AdminSymbol>>().toLens()

        "admin/symbol" meta {
            operationId = "list-symbols"
            summary = "List Symbols"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.OK, responseBody to listOf())
        } bindContract Method.GET to { _ ->
            Response(Status.OK).with(
                responseBody of transaction {
                    SymbolEntity.all().map {
                        AdminSymbol(
                            chainId = it.chainId.value,
                            name = it.name,
                            description = it.description,
                            contractAddress = it.contractAddress,
                            decimals = it.decimals,
                            iconUrl = it.iconUrl,
                            withdrawalFee = it.withdrawalFee,
                            addToWallets = it.addToWallets,
                        )
                    }.toList()
                },
            )
        }
    }

    private val symbolNamePathParam = Path.string().of("symbolName", "Symbol Name")

    private val patchSymbol: ContractRoute = run {
        val requestBody = Body.auto<AdminSymbol>().toLens()

        "admin/symbol" / symbolNamePathParam meta {
            operationId = "patch-symbol"
            summary = "Patch Symbol"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.OK)
        } bindContract Method.PATCH to { symbolName ->
            { request ->
                val payload = requestBody(request)
                runBlocking {
                    transaction {
                        val symbol = SymbolEntity.forName(symbolName)
                        symbol.description = payload.description
                        symbol.withdrawalFee = payload.withdrawalFee
                        symbol.addToWallets = payload.addToWallets
                        symbol.iconUrl = payload.iconUrl
                    }
                }
                Response(Status.OK)
            }
        }
    }

    private val createMarket: ContractRoute = run {
        val requestBody = Body.auto<AdminMarket>().toLens()
        "admin/market" meta {
            operationId = "create-market"
            summary = "Create Market"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            receiving(
                requestBody to AdminMarket(
                    id = MarketId("ONE:1/TWO:2"),
                    tickSize = "0.1".toBigDecimal(),
                    minFee = BigInteger.TEN,
                    lastPrice = "10.01".toBigDecimal(),
                ),
            )
            returning(Status.CREATED)
        } bindContract Method.POST to { request ->
            transaction {
                runBlocking {
                    val payload = requestBody(request)
                    val baseSymbol = SymbolEntity.forName(payload.id.baseSymbol())
                    val quoteSymbol = SymbolEntity.forName(payload.id.quoteSymbol())
                    val market = MarketEntity.create(
                        baseSymbol = baseSymbol,
                        quoteSymbol = quoteSymbol,
                        tickSize = payload.tickSize,
                        minFee = payload.minFee,
                        lastPrice = payload.lastPrice,
                    )
                    sequencerClient.createMarket(
                        marketId = market.id.value.value,
                        tickSize = market.tickSize,
                        baseDecimals = market.baseSymbol.decimals.toInt(),
                        quoteDecimals = market.quoteSymbol.decimals.toInt(),
                        minFee = market.minFee.fromFundamentalUnits(market.quoteSymbol.decimals),
                    )
                    sequencerClient.setMarketMinFees(mapOf(market.id.value to market.minFee))
                }
            }
            Response(Status.CREATED)
        }
    }

    private val listMarkets: ContractRoute = run {
        val responseBody = Body.auto<List<AdminMarket>>().toLens()

        "admin/market" meta {
            operationId = "list-markets"
            summary = "List Markets"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.OK, responseBody to listOf())
        } bindContract Method.GET to { _ ->
            Response(Status.OK).with(
                responseBody of transaction {
                    MarketEntity.all().map {
                        AdminMarket(
                            id = it.id.value,
                            tickSize = it.tickSize,
                            lastPrice = it.lastPrice,
                            minFee = it.minFee,
                        )
                    }.toList()
                },
            )
        }
    }

    private val baseSymbolNamePathParam = Path.string().of("baseSymbolName", "Base Symbol Name")
    private val quoteSymbolNamePathParam = Path.string().of("quoteSymbolName", "Quote Symbol Name")

    private val patchMarket: ContractRoute = run {
        val requestBody = Body.auto<AdminMarket>().toLens()

        "admin/market" / baseSymbolNamePathParam / quoteSymbolNamePathParam meta {
            operationId = "patch-market"
            summary = "Patch Market"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.OK)
        } bindContract Method.PATCH to { baseSymbol, quoteSymbol ->
            { request ->
                val payload = requestBody(request)
                runBlocking {
                    val marketId = MarketId("$baseSymbol/$quoteSymbol")
                    transaction {
                        val market = MarketEntity.findById(marketId)!!
                        market.minFee = payload.minFee
                    }
                    sequencerClient.setMarketMinFees(mapOf(marketId to payload.minFee))
                }
                Response(Status.OK)
            }
        }
    }
    val routes = listOf(
        createSymbol,
        listSymbols,
        patchSymbol,
        createMarket,
        listMarkets,
        patchMarket,
    )
}
