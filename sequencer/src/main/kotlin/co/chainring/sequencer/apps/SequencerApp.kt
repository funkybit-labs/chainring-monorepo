package co.chainring.sequencer.apps

import co.chainring.sequencer.core.inputQueue
import co.chainring.sequencer.core.outputQueue
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderResponse
import co.chainring.sequencer.proto.sequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.thread

class SequencerApp : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread

    override fun start() {
        logger.info { "Starting Sequencer App" }
        sequencerThread = thread(start = true, name = "sequencer", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("sequencer")
            val outputAppender = outputQueue.acquireAppender()
            while (!stop) {
                inputTailer.readingDocument().use { dc ->
                    if (dc.isPresent) {
                        dc.wire()?.read()?.bytes { bytes ->
                            val order = Order.parseFrom(bytes.toByteArray())
                            // TODO - process order
                            outputAppender.writingDocument().use {
                                it.wire()?.write()?.bytes(
                                    sequencerResponse {
                                        guid = order.guid
                                        disposition = OrderResponse.OrderDisposition.Accepted
                                    }.toByteArray(),
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
