package xyz.funkybit.apps.api

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
import xyz.funkybit.apps.api.middleware.adminSecurity
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.BigDecimalJson
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.FeeRate
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.WithdrawalFee
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.utils.IconUtils.resolveSymbolUrl
import xyz.funkybit.core.utils.fromFundamentalUnits
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
                val url = resolveSymbolUrl(payload.iconUrl)
                val symbol = transaction {
                    SymbolEntity.create(
                        chainId = payload.chainId,
                        name = payload.name.replace(Regex(":.*$"), ""),
                        contractAddress = payload.contractAddress,
                        decimals = payload.decimals,
                        addToWallets = payload.addToWallets,
                        withdrawalFee = BigInteger.ZERO,
                        description = payload.description,
                        iconUrl = url,
                    )
                }
                sequencerClient.setWithdrawalFees(
                    listOf(
                        WithdrawalFee(Symbol(symbol.name), payload.withdrawalFee),
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
                    val url = resolveSymbolUrl(payload.iconUrl)
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
                        symbol.addToWallets = payload.addToWallets
                        symbol.iconUrl = url
                        symbol.updatedAt = Clock.System.now()
                        symbol.updatedBy = request.principal.toString()
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
                            createdBy = request.principal.toString(),
                            minFee = BigInteger.ZERO,
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
                    minFee = payload.minFee.fromFundamentalUnits(quoteSymbol.decimals),
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

    private val listAdmins: ContractRoute = run {
        val responseBody = Body.auto<List<Address>>().toLens()

        "admin/admin" meta {
            operationId = "list-admins"
            summary = "List Admins"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(
                Status.OK,
                responseBody to listOf(
                    EvmAddress("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                ),
            )
        } bindContract Method.GET to { _ ->
            Response(Status.OK).with(
                responseBody of transaction {
                    WalletEntity.getAdminAddresses()
                },
            )
        }
    }

    private val adminAddressPathParam = Path.map(Address::auto, Address::toString).of("adminAddress", "Admin Address")
    private val addAdmin: ContractRoute = run {
        "admin/admin" / adminAddressPathParam meta {
            operationId = "add-admin"
            summary = "Add Admin"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.CREATED)
        } bindContract Method.PUT to { address ->
            { _ ->
                transaction {
                    WalletEntity.getOrCreateWithUser(address).isAdmin = true
                }
                Response(Status.CREATED)
            }
        }
    }

    private val removeAdmin: ContractRoute = run {
        "admin/admin" / adminAddressPathParam meta {
            operationId = "remove-admin"
            summary = "Remove Admin"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            returning(Status.NO_CONTENT)
        } bindContract Method.DELETE to { address ->
            { _ ->
                transaction {
                    WalletEntity.findByAddress(address)?.let { it.isAdmin = false }
                }
                Response(Status.NO_CONTENT)
            }
        }
    }

    private val setFeeRates: ContractRoute = run {
        val requestBody = Body.auto<FeeRates>().toLens()
        "admin/fee-rates" meta {
            operationId = "set-fee-rates"
            summary = "Set Fee Rates"
            security = signedTokenSecurity.and(adminSecurity)
            tags += listOf(Tag("admin"))
            receiving(requestBody to FeeRates(FeeRate(100), FeeRate(200)))
            returning(Status.CREATED)
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)
            runBlocking {
                sequencerClient.setFeeRates(FeeRates(payload.maker, payload.taker)).let { response ->
                    if (response.hasError()) {
                        throw RequestProcessingError("Unable to set fee rates in sequencer: ${response.error}")
                    }
                }
            }
            Response(Status.CREATED)
        }
    }

    val routes = listOf(
        createSymbol,
        listSymbols,
        patchSymbol,
        createMarket,
        listMarkets,
        patchMarket,
        listAdmins,
        addAdmin,
        removeAdmin,
        setFeeRates,
    )
}
