package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.string
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.AccountConfigurationApiResponse
import xyz.funkybit.apps.api.model.AuthorizedAddress
import xyz.funkybit.apps.api.model.Chain
import xyz.funkybit.apps.api.model.ConfigurationApiResponse
import xyz.funkybit.apps.api.model.DeployedContract
import xyz.funkybit.apps.api.model.FeeRates
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.Role
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.apps.api.model.toSymbolInfo
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainTable
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.pointsBalances
import xyz.funkybit.core.model.db.sum
import xyz.funkybit.core.utils.TestnetChallengeUtils
import java.math.BigDecimal
import java.math.BigInteger

class ConfigRoutes(private val faucetMode: FaucetMode) {
    private val logger = KotlinLogging.logger {}

    val getConfiguration: ContractRoute = run {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        "config" meta {
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
                                name = "Bitlayer",
                                contracts = listOf(
                                    DeployedContract(
                                        name = "Exchange",
                                        address = EvmAddress("0x0000000000000000000000000000000000000000"),
                                    ),
                                ),
                                symbols = listOf(
                                    SymbolInfo(
                                        name = "ETH",
                                        description = "Ethereum",
                                        contractAddress = null,
                                        decimals = 18u,
                                        faucetSupported = false,
                                        iconUrl = "https://icons/eth.svg",
                                        withdrawalFee = BigInteger.ONE,
                                    ),
                                    SymbolInfo(
                                        name = "USDC",
                                        description = "USD Coin",
                                        contractAddress = EvmAddress("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                        decimals = 18u,
                                        faucetSupported = false,
                                        iconUrl = "https://icons/usdc.svg",
                                        withdrawalFee = BigInteger.ONE,
                                    ),
                                ),
                                jsonRpcUrl = "https://demo-anvil.funkybit.fun",
                                blockExplorerNetName = "funkybit Demo BitLayer",
                                blockExplorerUrl = "https://demo-otterscan.funkybit.fun",
                                networkType = NetworkType.Evm,
                            ),
                        ),
                        markets = listOf(
                            Market(
                                id = MarketId("USDC/DAI"),
                                baseSymbol = Symbol("USDC"),
                                baseDecimals = 18,
                                quoteSymbol = Symbol("DAI"),
                                quoteDecimals = 6,
                                tickSize = "0.01".toBigDecimal(),
                                lastPrice = "0.995".toBigDecimal(),
                                minFee = BigInteger.ONE,
                            ),
                        ),
                        feeRates = FeeRates(
                            maker = BigDecimal("1.0"),
                            taker = BigDecimal("2.0"),
                        ),
                    ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        ConfigurationApiResponse(
                            chains = ChainEntity.all().orderBy(ChainTable.id to SortOrder.ASC).map { chain ->
                                Chain(
                                    id = chain.id.value,
                                    name = chain.name,
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
                                            faucetSupported = it.faucetSupported(faucetMode),
                                            iconUrl = it.iconUrl,
                                            withdrawalFee = it.withdrawalFee,
                                        )
                                    },
                                    jsonRpcUrl = chain.jsonRpcUrl,
                                    blockExplorerNetName = chain.blockExplorerNetName,
                                    blockExplorerUrl = chain.blockExplorerUrl,
                                    networkType = chain.networkType,
                                )
                            },
                            markets = MarketEntity.all().map { market ->
                                Market(
                                    id = market.id.value,
                                    baseSymbol = Symbol(market.baseSymbol.name),
                                    baseDecimals = market.baseSymbol.decimals.toInt(),
                                    quoteSymbol = Symbol(market.quoteSymbol.name),
                                    quoteDecimals = market.quoteSymbol.decimals.toInt(),
                                    tickSize = market.tickSize,
                                    lastPrice = market.lastPrice,
                                    minFee = market.minFee,
                                )
                            }.sortedWith(compareBy({ it.baseSymbol.value }, { it.quoteSymbol.value })),
                            feeRates = FeeRates(xyz.funkybit.core.model.db.FeeRates.fetch()),
                        ),
                )
            }
        }
    }

    val getAccountConfiguration: ContractRoute = run {
        val responseBody = Body.auto<AccountConfigurationApiResponse>().toLens()

        "account-config" meta {
            operationId = "account-config"
            summary = "Get account configuration"
            security = signedTokenSecurity
            tags += listOf(Tag("configuration"))
            returning(
                Status.OK,
                responseBody to
                    AccountConfigurationApiResponse(
                        role = Role.User,
                        newSymbols = listOf(
                            SymbolInfo(
                                name = "FUNK",
                                description = "funkybit Token",
                                contractAddress = EvmAddress("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                decimals = 18u,
                                faucetSupported = true,
                                iconUrl = "https://icons/funk.svg",
                                withdrawalFee = BigInteger.ZERO,
                            ),
                        ),
                        authorizedAddresses = listOf(
                            AuthorizedAddress(EvmAddress.zero, NetworkType.Evm),
                        ),
                        testnetChallengeStatus = TestnetChallengeStatus.Unenrolled,
                        testnetChallengeDepositSymbol = "USDC:31337",
                        testnetChallengeDepositContract = null,
                        nickName = "Nick Name",
                        avatarUrl = "https://icons/avatar.svg",
                        inviteCode = "WYZFRORSQDI",
                        pointsBalance = BigDecimal.ZERO,
                    ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                val wallet = request.principal

                Response(Status.OK).with(
                    responseBody of
                        AccountConfigurationApiResponse(
                            role = if (wallet.isAdmin) Role.Admin else Role.User,
                            newSymbols = SymbolEntity.symbolsToAddToWallet(wallet.address).map {
                                it.toSymbolInfo(faucetMode)
                            },
                            authorizedAddresses = wallet.authorizedAddresses().map { (address, networkType) ->
                                AuthorizedAddress(address, networkType)
                            },
                            testnetChallengeStatus = wallet.user.testnetChallengeStatus,
                            testnetChallengeDepositSymbol = if (TestnetChallengeUtils.enabled) TestnetChallengeUtils.depositSymbolName else null,
                            testnetChallengeDepositContract = if (TestnetChallengeUtils.enabled) TestnetChallengeUtils.depositSymbol().contractAddress else null,
                            nickName = wallet.user.nickname,
                            avatarUrl = wallet.user.avatarUrl,
                            inviteCode = wallet.user.inviteCode,
                            pointsBalance = request.principal.user.pointsBalances().sum(),
                        ),
                )
            }
        }
    }

    private val symbolNamePathParam = Path.string().of("symbolName", "Symbol Name")

    val markSymbolAsAdded: ContractRoute = run {
        "account-config" / symbolNamePathParam meta {
            operationId = "symbol-added"
            summary = "Mark symbol as added"
            security = signedTokenSecurity
            tags += listOf(Tag("configuration"))
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.POST to { symbolName ->
            { request ->
                transaction {
                    val wallet = request.principal
                    if (!wallet.addedSymbols.contains(symbolName)) {
                        wallet.addedSymbols += symbolName
                    }

                    Response(Status.NO_CONTENT)
                }
            }
        }
    }
}
