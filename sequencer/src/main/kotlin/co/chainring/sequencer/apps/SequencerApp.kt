package co.chainring.sequencer.apps

import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.inputQueue
import co.chainring.sequencer.core.outputQueue
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toMarketId
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.marketCreated
import co.chainring.sequencer.proto.sequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.thread

class SequencerApp : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread
    private var markets = mutableMapOf<String, Market>()

    override fun start() {
        logger.info { "Starting Sequencer App" }
        sequencerThread = thread(start = true, name = "sequencer", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("sequencer")
            val outputAppender = outputQueue.acquireAppender()
            while (!stop) {
                inputTailer.readingDocument().use { dc ->
                    if (dc.isPresent) {
                        val startTime = System.nanoTime()
                        dc.wire()?.read()?.bytes { bytes ->
                            val request = SequencerRequest.parseFrom(bytes.toByteArray())
                            val response = when (request.type) {
                                SequencerRequest.Type.AddMarket -> {
                                    val market = request.addMarket!!
                                    var error: SequencerError? = null
                                    if (markets.containsKey(market.marketId)) {
                                        error = SequencerError.MarketExists
                                    } else {
                                        markets[market.marketId] = Market(
                                            market.marketId.toMarketId(),
                                            market.tickSize.toBigDecimal(),
                                            market.marketPrice.toBigDecimal(),
                                            market.maxLevels,
                                            market.maxOrdersPerLevel,
                                        )
                                    }
                                    sequencerResponse {
                                        this.guid = market.guid
                                        this.sequence = dc.index()
                                        this.processingTime = System.nanoTime() - startTime
                                        error?.let { this.error = it } ?: run {
                                            this.marketsCreated.add(
                                                marketCreated {
                                                    this.marketId = market.marketId
                                                    this.tickSize = market.tickSize
                                                },
                                            )
                                        }
                                    }
                                }
                                SequencerRequest.Type.ApplyOrderBatch -> {
                                    var ordersChanged: List<OrderChanged> = emptyList()
                                    var trades: List<TradeCreated> = emptyList()
                                    val orderBatch = request.orderBatch!!
                                    var error: SequencerError? = null
                                    if (markets.containsKey(orderBatch.marketId)) {
                                        val result = markets[orderBatch.marketId]!!.addOrders(orderBatch.ordersToAddList)
                                        ordersChanged = result.ordersChanged
                                        trades = result.createdTrades
                                    } else {
                                        error = SequencerError.UnknownMarket
                                    }
                                    sequencerResponse {
                                        this.sequence = dc.index()
                                        this.processingTime = System.nanoTime() - startTime
                                        this.guid = orderBatch.guid
                                        this.ordersChanged.addAll(ordersChanged)
                                        this.tradesCreated.addAll(trades)
                                        error?.let {
                                            this.error = it
                                        }
                                    }
                                }

                                null, SequencerRequest.Type.UNRECOGNIZED -> {
                                    sequencerResponse {
                                        this.sequence = dc.index()
                                        this.processingTime = System.nanoTime() - startTime
                                        this.guid = request.guid
                                        this.error = SequencerError.UnknownRequest
                                    }
                                }
                            }
                            outputAppender.writingDocument().use {
                                it.wire()?.write()?.bytes(
                                    response.toByteArray(),
                                )
                            }
                        }
                    }
                }
            }
        }
        logger.info { "Sequencer App started" }
    }

    override fun stop() {
        logger.info { "Stopping Sequencer App" }
        stop = true
        sequencerThread.join(100)
        sequencerThread.stop()
        logger.info { "Sequencer App stopped" }
    }
}
