package co.chainring.mocker

import co.chainring.apps.api.model.Market
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.MarketId
import co.chainring.core.utils.TraceRecorder
import co.chainring.core.utils.humanReadable
import co.chainring.mocker.core.DeterministicHarmonicPriceMovement
import co.chainring.mocker.core.Maker
import co.chainring.mocker.core.Taker
import co.chainring.mocker.core.toFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.math.BigDecimal
import java.util.Timer
import kotlin.concurrent.timerTask
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys


val start = Clock.System.now()

val warmupInterval = 1.minutes
val increaseLoadInterval = 5.minutes
val onMaxLoadInterval = 10.minutes

const val initialTakers = 5
const val maxTakers = 100
val newTakerInterval = increaseLoadInterval / (maxTakers - initialTakers)

const val initialMakers = 1
const val maxMakers = 2
val newMakerInterval = increaseLoadInterval / (maxMakers - initialMakers)

val statsInterval = 1.minutes
var statsPeriodStart = Clock.System.now()

val logger = KotlinLogging.logger {}
val statsOutputFile = getFilename()


fun main() {
    val timer = Timer()
    val makers = mutableListOf<Maker>()
    val takers = mutableListOf<Taker>()

    val usdcDai = Market(MarketId("USDC/DAI"), Symbol("USDC"), 6, Symbol("DAI"), 18, 0.05.toBigDecimal())
    val priceFunction = DeterministicHarmonicPriceMovement.generateRandom(initialValue = 17.0, maxFluctuation = 1.5)

    // schedule metrics
    val statsTask = timerTask {
        val now = Clock.System.now()
        TraceRecorder.full.generateStatsAndFlush(
            header = "Stats for last ${humanReadable(now - statsPeriodStart)} (${humanReadable(now - start)} elapsed since test start). " +
                    "Running ${makers.size} makers and ${takers.size} takers."
        ).let {
            val statsOutputFile = File(statsOutputFile)
            logger.debug { "Writing stats to: ${statsOutputFile.absolutePath}" }
            statsOutputFile.appendText(it, Charsets.UTF_8)
        }
        statsPeriodStart = now
    }
    timer.scheduleAtFixedRate(statsTask, statsInterval.inWholeMilliseconds, statsInterval.inWholeMilliseconds)

    // initial load
    (1..initialMakers).map {
        makers.add(startMaker(usdcDai, 10000.0.toBigDecimal(), 5000.0.toBigDecimal()))
    }
    (1..initialTakers).map {
        takers.add(startTaker(usdcDai, 100.0.toBigDecimal(), 50.0.toBigDecimal(), priceFunction))
    }
    // wait for system to warm caches
    Thread.sleep(warmupInterval.inWholeMilliseconds)


    // gradually increase load
    timer.scheduleAtFixedRate(timerTask {
        if (makers.size >= maxMakers) {
            logger.debug { "Max number of makers achieved" }
            this.cancel()
        } else {
            logger.debug { "Starting maker #${makers.size + 1}" }
            makers.add(startMaker(usdcDai, 10000.0.toBigDecimal(), 5000.0.toBigDecimal()))
        }
    }, 0, newMakerInterval.inWholeMilliseconds)
    timer.scheduleAtFixedRate(timerTask {
        if (takers.size >= maxTakers) {
            logger.debug { "Max number of takers achieved" }
            this.cancel()
        } else {
            logger.debug { "Starting taker #${takers.size + 1}" }
            takers.add(startTaker(usdcDai, 100.0.toBigDecimal(), 50.0.toBigDecimal(), priceFunction))
        }
    }, 0, newTakerInterval.inWholeMilliseconds)

    // run on max load after rum up
    Thread.sleep(increaseLoadInterval.inWholeMilliseconds + onMaxLoadInterval.inWholeMilliseconds)

    // tear down
    statsTask.cancel()
    makers.forEach {
        it.stop()
    }
    takers.forEach {
        it.stop()
    }
    timer.cancel()
}

fun startMaker(market: Market, baseAssetAmount: BigDecimal, quoteAssetAmount: BigDecimal, keyPair: ECKeyPair = Keys.createEcKeyPair()): Maker {
    val baseAssetBtc = market.baseSymbol.value.startsWith("BTC")
    val quoteAssetBtc = market.quoteSymbol.value.startsWith("BTC")
    val baseAsset = market.baseSymbol.value to baseAssetAmount.toFundamentalUnits(market.baseDecimals)
    val quoteAsset = market.quoteSymbol.value to quoteAssetAmount.toFundamentalUnits(market.quoteDecimals)

    val maker = Maker(
        marketIds = listOf(market.id),
        tightness = 5, skew = 0, levels = 11,
        nativeAssets = when {
            baseAssetBtc && quoteAssetBtc -> listOf(baseAsset, quoteAsset).toMap()
            baseAssetBtc -> listOf(baseAsset).toMap()
            quoteAssetBtc -> listOf(quoteAsset).toMap()
            else -> emptyMap()
        },
        assets = when {
            baseAssetBtc && quoteAssetBtc -> emptyMap()
            baseAssetBtc -> mapOf(quoteAsset)
            quoteAssetBtc -> mapOf(baseAsset)
            else -> mapOf(baseAsset, quoteAsset)
        },
        keyPair = keyPair
    )
    maker.start()
    return maker
}

fun startTaker(market: Market, baseAssetAmount: BigDecimal, quoteAssetAmount: BigDecimal, priceFunction: DeterministicHarmonicPriceMovement): Taker {
    val baseAssetBtc = market.baseSymbol.value.startsWith("BTC")
    val quoteAssetBtc = market.quoteSymbol.value.startsWith("BTC")
    val baseAsset = market.baseSymbol.value to baseAssetAmount.toFundamentalUnits(market.baseDecimals)
    val quoteAsset = market.quoteSymbol.value to quoteAssetAmount.toFundamentalUnits(market.quoteDecimals)

    val taker = Taker(
        marketIds = listOf(market.id),
        rate = Random.nextLong(10000, 30000),
        nativeAssets = when {
            baseAssetBtc && quoteAssetBtc -> listOf(baseAsset, quoteAsset).toMap()
            baseAssetBtc -> listOf(baseAsset).toMap()
            quoteAssetBtc -> listOf(quoteAsset).toMap()
            else -> emptyMap()
        },
        assets = when {
            baseAssetBtc && quoteAssetBtc -> emptyMap()
            baseAssetBtc -> mapOf(quoteAsset)
            quoteAssetBtc -> mapOf(baseAsset)
            else -> mapOf(baseAsset, quoteAsset)
        },
        priceCorrectionFunction = priceFunction,
    )
    taker.start()
    return taker
}

fun getFilename(): String {
    val envFilename = System.getenv("LOADTEST_STATS_FILENAME")
    if (envFilename != null && envFilename.isNotBlank()) return envFilename

    val now = Clock.System.now()
    val localDateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val formattedDate = "${localDateTime.year}-${localDateTime.monthNumber.toString().padStart(2, '0')}-${localDateTime.dayOfMonth.toString().padStart(2, '0')}_"
    val formattedTime = "${localDateTime.hour.toString().padStart(2, '0')}-${localDateTime.minute.toString().padStart(2, '0')}-${localDateTime.second.toString().padStart(2, '0')}"

    return "loadtest_stats_$formattedDate$formattedTime.log"
}
