package co.chainring.mocker

import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.utils.TraceRecorder
import co.chainring.integrationtests.utils.humanReadable
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime


val start = Clock.System.now()

val warmupInterval = 1.minutes
val increaseLoadInterval = 5.minutes
val onMaxLoadInterval = 5.minutes

const val initialTakers = 5
const val maxTakers = 100
val newTakerInterval = increaseLoadInterval / (maxTakers - initialTakers)

const val initialMakers = 1
const val maxMakers = 2
val newMakerInterval = increaseLoadInterval / (maxMakers - initialMakers)

val statsInterval = 10.seconds
var statsPeriodStart = Clock.System.now()

val logger = KotlinLogging.logger {}
val statsOutputFile = getFilename()


fun main() {
    val timer = Timer()
    val makers = mutableListOf<Maker>()
    val takers = mutableListOf<Taker>()

    val usdcDai = MarketId("USDC/DAI")

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
        makers.add(startMaker(usdcDai))
    }
    (1..initialTakers).map {
        takers.add(startTaker(usdcDai))
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
            makers.add(startMaker(usdcDai))
        }
    }, 0, newMakerInterval.inWholeMilliseconds)
    timer.scheduleAtFixedRate(timerTask {
        if (takers.size >= maxTakers) {
            logger.debug { "Max number of takers achieved" }
            this.cancel()
        } else {
            logger.debug { "Starting taker #${takers.size + 1}" }
            takers.add(startTaker(usdcDai))
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

private fun startMaker(market: MarketId): Maker {
    val maker = Maker(
        tightness = 5, skew = 0, levels = 10,
        native = BigDecimal.TEN.movePointRight(18).toBigInteger(),
        assets = mapOf(
            //"ETH" to 200.toFundamentalUnits(18),
            "USDC" to 10000.toFundamentalUnits(6),
            "DAI" to 5000.toFundamentalUnits(18)
        )
    )
    maker.start(listOf(market))
    return maker
}

private fun startTaker(market: MarketId): Taker {
    val taker = Taker(
        rate = Random.nextLong(5000, 15000),
        sizeFactor = Random.nextDouble(5.0, 20.0),
        native = null,
        assets = mapOf(
            "USDC" to 100.toFundamentalUnits(6),
            "DAI" to 50.toFundamentalUnits(18)
        )
    )
    taker.start(listOf(market))
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