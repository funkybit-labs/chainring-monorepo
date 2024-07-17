package co.chainring.apps.api

import co.chainring.apps.api.middleware.adminSecurity
import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.apps.api.model.RequestProcessingError
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.WithdrawalFee
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.utils.fromFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
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
            runBlocking {
                val payload = requestBody(request)
                val symbol = transaction {
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
                sequencerClient.setWithdrawalFees(
                    listOf(
                        WithdrawalFee(Symbol(symbol.name), symbol.withdrawalFee),
                    ),
                ).let { response ->
                    if (response.hasError()) {
                        try {
                            transaction {
                                symbol.delete()
                            }
                        } catch (e: Exception) {
                            throw RequestProcessingError("Unable to set withdrawal fees in sequencer: ${response.error}, and could not clean up symbol in DB: ${e.message}")
                        }
                        throw RequestProcessingError("Unable to set withdrawal fees in sequencer: ${response.error}")
                    }
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
            returning(
                Status.OK,
                responseBody to listOf(
                    AdminSymbol(
                        chainId = ChainId(31337u),
                        name = "NAME",
                        description = "Description",
                        contractAddress = null,
                        decimals = 18u,
                        iconUrl = "icon.svg",
                        withdrawalFee = BigInteger.valueOf(100),
                        addToWallets = false,
                    ),
                ),
            )
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
                    val originalData = transaction {
                        val symbol = SymbolEntity.forName(symbolName)
                        val originalData = AdminSymbol(
                            symbol.chainId.value,
                            symbol.name,
                            symbol.description,
                            symbol.contractAddress,
                            symbol.decimals,
                            symbol.iconUrl,
                            symbol.withdrawalFee,
                            symbol.addToWallets,
                        )
                        symbol.description = payload.description
                        symbol.withdrawalFee = payload.withdrawalFee
                        symbol.addToWallets = payload.addToWallets
                        symbol.iconUrl = payload.iconUrl
                        symbol.updatedAt = Clock.System.now()
                        symbol.updatedBy = request.principal.value
                        originalData
                    }
                    if (originalData.withdrawalFee != payload.withdrawalFee) {
                        sequencerClient.setWithdrawalFees(
                            listOf(
                                WithdrawalFee(Symbol(symbolName), payload.withdrawalFee),
                            ),
                        ).let { response ->
                            if (response.hasError()) {
                                try {
                                    transaction {
                                        val symbol = SymbolEntity.forName(symbolName)
                                        symbol.description = originalData.description
                                        symbol.withdrawalFee = originalData.withdrawalFee
                                        symbol.addToWallets = originalData.addToWallets
                                        symbol.iconUrl = originalData.iconUrl
                                    }
                                } catch (e: Exception) {
                                    throw RequestProcessingError("Unable to set withdrawal fees in sequencer: ${response.error}, and could not revert symbol in DB: ${e.message}")
                                }
                                throw RequestProcessingError("Unable to set withdrawal fees in sequencer: ${response.error}")
                            }
                        }
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
            runBlocking {
                val payload = requestBody(request)
                val(market, baseSymbol, quoteSymbol) = transaction {
                    val baseSymbol = SymbolEntity.forName(payload.id.baseSymbol())
                    val quoteSymbol = SymbolEntity.forName(payload.id.quoteSymbol())
                    Triple(
                        MarketEntity.create(
                            baseSymbol = baseSymbol,
                            quoteSymbol = quoteSymbol,
                            tickSize = payload.tickSize,
                            lastPrice = payload.lastPrice,
                            createdBy = request.principal.value,
                            minFee = payload.minFee,
                        ),
                        baseSymbol,
                        quoteSymbol,
                    )
                }
                sequencerClient.createMarket(
                    marketId = market.id.value.value,
                    tickSize = market.tickSize,
                    baseDecimals = baseSymbol.decimals.toInt(),
                    quoteDecimals = quoteSymbol.decimals.toInt(),
                    minFee = market.minFee.fromFundamentalUnits(quoteSymbol.decimals),
                ).let { response ->
                    if (response.hasError()) {
                        try {
                            transaction { market.delete() }
                        } catch (e: Exception) {
                            throw RequestProcessingError("Unable to create market in sequencer: ${response.error}, and could not clean up market in DB: ${e.message}")
                        }
                        throw RequestProcessingError("Unable to create market in sequencer: ${response.error}")
                    }
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
            returning(
                Status.OK,
                responseBody to listOf(
                    AdminMarket(
                        id = MarketId("ONE:1/TWO:2"),
                        tickSize = "0.1".toBigDecimal(),
                        minFee = BigInteger.TEN,
                        lastPrice = "10.01".toBigDecimal(),
                    ),
                ),
            )
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
                        market.updatedAt = Clock.System.now()
                        market.updatedBy = request.principal.value
                    }
                    sequencerClient.setMarketMinFees(mapOf(marketId to payload.minFee)).let { response ->
                        if (response.hasError()) {
                            throw RequestProcessingError("Unable to set market min fees in sequencer: ${response.error}")
                        }
                    }
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
