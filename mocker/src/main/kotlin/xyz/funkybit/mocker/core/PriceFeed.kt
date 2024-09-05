package xyz.funkybit.mocker.core

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.integrationtests.utils.json
import kotlin.concurrent.thread

class PriceFeed(marketIds: List<MarketId>, val onPriceUpdate: (Map<MarketId, Double>) -> Unit) {
    private val rawBaseAndQuoteByMarket = marketIds.associateWith {
        Pair(
            it.baseSymbol().replace(Regex(":.*$"), ""),
            it.quoteSymbol().replace(Regex(":.*$"), ""),
        )
    }
    private val baseSymbols = rawBaseAndQuoteByMarket.values.map { it.first }.toSet()
    private val quoteSymbols = rawBaseAndQuoteByMarket.values.map { it.second }.toSet()
    private var stopping = false
    private val httpClient = OkHttpClient.Builder().build()

    private val prices: MutableMap<MarketId, Double> = marketIds.associateWith { 0.0 }.toMutableMap()

    private lateinit var priceThread: Thread

    private val apiKey = System.getenv("CRYPTOCOMPARE_API_KEY")

    private val logger = KotlinLogging.logger {}

    fun start() {
        priceThread = thread(start = true, isDaemon = false, name = "mock-price-feed") {
            while (!stopping) {
                try {
                    val request = httpClient.newCall(
                        Request.Builder()
                            .url(
                                "https://min-api.cryptocompare.com/data/pricemulti?${if (apiKey != null) "api_key=$apiKey&" else ""}fsyms=${baseSymbols.joinToString(",")}&tsyms=${quoteSymbols.joinToString(",")}"
                            )
                            .get()
                            .build()
                    )
                    val response = request.execute()
                    if (response.isSuccessful) {
                        // update price for each market
                        val receivedPrices = json.decodeFromString<Map<String,Map<String,Double>>>(response.body!!.string())
                        prices.keys.forEach { marketId ->
                            val(marketBase, marketQuote) = rawBaseAndQuoteByMarket[marketId]!!
                            receivedPrices[marketBase]?.let { basePrices ->
                                basePrices[marketQuote]?.let { prices[marketId] = it }
                            }
                        }
                        onPriceUpdate(prices.toMap())
                    } else {
                        logger.debug { "Unable to get prices from ${request.request().url}: ${response.code}: ${response.body}" }
                    }
                    Thread.sleep(2000L)
                } catch (e: InterruptedException) {
                    stopping = true
                }
            }
        }
    }

    fun stop() {
        stopping = true
        priceThread.interrupt()
        priceThread.join()
    }
}