package xyz.funkybit.mocker

import xyz.funkybit.apps.api.middleware.HttpTransactionLogger
import xyz.funkybit.apps.api.middleware.RequestProcessingExceptionHandler
import xyz.funkybit.integrationtests.utils.ApiClient
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.utils.TraceRecorder
import xyz.funkybit.mocker.core.PriceFunction
import xyz.funkybit.mocker.core.LiquidityPlacement
import xyz.funkybit.mocker.core.Maker
import xyz.funkybit.mocker.core.Taker
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.time.Duration
import java.util.*
import kotlin.concurrent.timerTask
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import org.http4k.core.*
import org.http4k.filter.ServerFilters
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.PolyHandler
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import xyz.funkybit.integrationtests.utils.WalletKeyPair

fun main() {
    val logger = KotlinLogging.logger {}
    logger.info { "Starting mocker app" }

    try {
        MockerApp().start()
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start mocker app" }
        exitProcess(1)
    }
}

data class MarketParams(
    var desiredTakersCount: Int,
    var priceBaseline: BigDecimal,
    var initialBaseBalance: BigDecimal,
    var makerPrivateKeyHex: String,
    var priceStabilization: Boolean,
    var liquidityPlacement: LiquidityPlacement,
    val makers: MutableList<Maker> = mutableListOf(),
    val takers: MutableList<Taker> = mutableListOf()
)

class MockerApp(
    httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8000,
    config: List<String> = System.getenv("MARKETS")?.split(',') ?: emptyList()
) {
    private val logger = KotlinLogging.logger {}
    private val marketsConfig = mutableMapOf<MarketId, MarketParams>()
    private val marketsPriceFunctions = mutableMapOf<MarketId, PriceFunction>()

    init {
        config.forEach {
            val marketIdCleaned = it.replace(Regex("[:/]"), "_")
            marketsConfig[MarketId(it)] = MarketParams(
                desiredTakersCount = System.getenv("${marketIdCleaned}_TAKERS")?.toIntOrNull() ?: 20,
                priceBaseline = System.getenv("${marketIdCleaned}_PRICE_BASELINE")?.toBigDecimalOrNull() ?: BigDecimal("17.5"),
                initialBaseBalance = System.getenv("${marketIdCleaned}_INITIAL_BASE_BALANCE")?.toBigDecimalOrNull() ?: BigDecimal.ONE,
                makerPrivateKeyHex = System.getenv("${marketIdCleaned}_MAKER_PRIVATE_KEY_HEX") ?: ("0x" + Keys.createEcKeyPair().privateKey.toString(16)),
                priceStabilization = System.getenv("${marketIdCleaned}_MAKER_PRICE_STABILIZATION")?.toBoolean() ?: false,
                liquidityPlacement = System.getenv("${marketIdCleaned}_MAKER_LIQUIDITY_PLACEMENT")
                    ?.let { value ->
                        if (value.endsWith("%")) {
                            LiquidityPlacement.Relative(fraction = (value.removeSuffix("%").toDouble() / 100).toBigDecimal())
                        } else {
                            LiquidityPlacement.Absolute(value.toBigInteger())
                        }
                    }
                    ?: LiquidityPlacement.default
            )
        }
    }

    private val getConfigLens = Body.auto<Map<MarketId, Map<String, Int>>>().toLens()
    private val updateConfigLens = Body.auto<Map<String, Int>>().toLens()
    private val marketIdLens = Path.of("marketId")

    private val httpHandler = ServerFilters.InitialiseRequestContext(RequestContexts())
        .then(HttpTransactionLogger())
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1/config" bind Method.GET to { _: Request ->
                    val configResponse = marketsConfig.mapValues { (_, params) ->
                        mapOf(
                            "desiredTakersCount" to params.desiredTakersCount
                        )
                    }
                    Response(Status.OK).with(getConfigLens of configResponse)
                },
                "/v1/config/{marketId}" bind Method.POST to { request: Request ->
                    val marketId = MarketId(marketIdLens(request))
                    val newConfig = updateConfigLens(request)
                    val currentParams = marketsConfig[marketId]

                    if (currentParams != null) {
                        newConfig["desiredTakersCount"]?.let { currentParams.desiredTakersCount = it }

                        updateMarketActors()

                        Response(Status.OK).body("Updated configuration for market: $marketId")
                    } else {
                        Response(Status.NOT_FOUND).body("Market not found: $marketId")
                    }
                }
            ),
        )

    private fun updateMarketActors() {
        val currentMarkets = ApiClient().getConfiguration().markets.associateBy { it.id }

        marketsConfig.forEach { (marketId, params) ->
            logger.info { "Updating configuration for $marketId: (makers 1, desired takers: ${params.desiredTakersCount})" }
            val market = currentMarkets[marketId] ?: run {
                logger.info { "Market $marketId not found. Skipping." }
                return@forEach
            }

            val priceFunction = marketsPriceFunctions.getOrPut(marketId) {
                PriceFunction.generateDeterministicHarmonicMovement(
                    initialValue = params.priceBaseline.toDouble(),
                    maxFluctuation = market.tickSize.toDouble() * 30
                )
            }

            // Start maker
            if (params.makers.size == 0) {
                params.makers.add(
                    startMaker(
                        market = market,
                        marketPriceOverride = if (params.priceStabilization) params.priceBaseline else null,
                        liquidityPlacement = params.liquidityPlacement,
                        baseAssetAmount = params.initialBaseBalance * BigDecimal(100),
                        quoteAssetAmount = params.initialBaseBalance * params.priceBaseline * BigDecimal(100),
                        keyPair = WalletKeyPair.EVM.fromPrivateKeyHex(params.makerPrivateKeyHex),
                        usePriceFeed = System.getenv("MAKER_USE_PRICE_FEED")?.toBoolean() ?: false
                    )
                )
            }

            // Adjust takers count
            while (params.takers.size < params.desiredTakersCount) {
                params.takers.add(startTaker(market, params.initialBaseBalance * BigDecimal(2), params.initialBaseBalance * params.priceBaseline * BigDecimal(2), priceFunction))
            }
            while (params.takers.size > params.desiredTakersCount) {
                params.takers.removeAt(params.takers.size - 1).stop()
            }
        }
    }

    private val server = PolyHandler(
        httpHandler
    ).asServer(Netty(httpPort, ServerConfig.StopMode.Graceful(Duration.ofSeconds(1))))

    fun start() {
        logger.info { "Starting" }

        server.start()

        // schedule printings stats
        Timer().also {
            val metricsTask = timerTask {
                val totalMakers = marketsConfig.map { it.value.makers.size }.sum()
                val totalTakers = marketsConfig.map { it.value.takers.size }.sum()
                logger.debug {
                    TraceRecorder.full.generateStatsAndFlush(
                        header = "Running $totalMakers makers and $totalTakers takers in ${marketsConfig.size} markets"
                    )
                }
            }
            it.scheduleAtFixedRate(
                metricsTask,
                1.minutes.inWholeMilliseconds,
                1.minutes.inWholeMilliseconds,
            )
        }

        // start makers/takers
        updateMarketActors()

        logger.info { "Started" }
    }
}
