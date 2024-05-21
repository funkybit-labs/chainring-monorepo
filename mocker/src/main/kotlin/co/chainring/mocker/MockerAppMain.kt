package co.chainring.mocker

import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.model.db.MarketId
import co.chainring.core.utils.TraceRecorder
import co.chainring.mocker.core.Maker
import co.chainring.mocker.core.Taker
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
    var desiredMakersCount: Int,
    var desiredTakersCount: Int,
    var initialTakerBaseBalance: BigDecimal,
    var initialTakerQuoteBalance: BigDecimal,
    val makers: MutableList<Maker> = mutableListOf(),
    val takers: MutableList<Taker> = mutableListOf()
)

class MockerApp(
    httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8000,
    config: List<String> = System.getenv("MARKETS")?.split(',') ?: emptyList()
) {
    private val logger = KotlinLogging.logger {}
    private val marketsConfig = mutableMapOf<MarketId, MarketParams>()

    init {
        config.forEach {
            marketsConfig[MarketId(it)] = MarketParams(
                desiredMakersCount = System.getenv(it.replace("/", "_") + "_MAKERS")?.toIntOrNull() ?: 1,
                desiredTakersCount = System.getenv(it.replace("/", "_") + "_TAKERS")?.toIntOrNull() ?: 4,
                initialTakerBaseBalance = System.getenv(it.replace("/", "_") + "_INITIAL_TAKER_BASE_BALANCE")?.toBigDecimalOrNull() ?: BigDecimal.TEN,
                initialTakerQuoteBalance = System.getenv(it.replace("/", "_") + "_INITIAL_TAKER_QUOTE_BALANCE")?.toBigDecimalOrNull()
                    ?: BigDecimal.TEN
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
                            "desiredMakersCount" to params.desiredMakersCount,
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
                        newConfig["desiredMakersCount"]?.let { currentParams.desiredMakersCount = it }
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
            logger.info { "Updating configuration for $marketId: (desired makers ${params.desiredMakersCount}, desired takers: ${params.desiredTakersCount})" }
            val market = currentMarkets[marketId.value] ?: run {
                logger.info { "Market $marketId not found. Skipping." }
                return@forEach
            }

            // Adjust makers count
            while (params.makers.size < params.desiredMakersCount) {
                params.makers.add(
                    startMaker(
                        market,
                        params.initialTakerBaseBalance * BigDecimal(100),
                        params.initialTakerQuoteBalance * BigDecimal(100)
                    )
                )
            }
            while (params.makers.size > params.desiredMakersCount) {
                params.makers.removeAt(params.makers.size - 1).stop()
            }

            // Adjust takers count
            while (params.takers.size < params.desiredTakersCount) {
                params.takers.add(startTaker(market, params.initialTakerBaseBalance, params.initialTakerQuoteBalance))
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
                val totalMakers = marketsConfig.map { it.value.desiredMakersCount }.sum()
                val totalTakers = marketsConfig.map { it.value.desiredTakersCount }.sum()
                logger.debug {
                    TraceRecorder.full.generateStatsAndFlush(
                        header = "Running $totalMakers makers and $totalTakers takers in ${marketsConfig.size} markets"
                    )
                }
            }
            it.scheduleAtFixedRate(
                metricsTask,
                5.minutes.inWholeMilliseconds,
                5.minutes.inWholeMilliseconds,
            )
        }

        // start makers/takers
        updateMarketActors()

        logger.info { "Started" }
    }
}
