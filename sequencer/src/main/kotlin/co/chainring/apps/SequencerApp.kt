package co.chainring.apps

import co.chainring.core.inputQueue
import co.chainring.core.outputQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import sequencer.OrderOuterClass
import sequencer.sequencerResponse
import kotlin.concurrent.thread

class SequencerApp : BaseApp() {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var sequencerThread: Thread

    override fun start() {
        logger.info { "Starting Sequencer App" }
        sequencerThread = thread(start = true, name = "sequencer", isDaemon = true) {
            val inputTailer = inputQueue.createTailer("sequencer")
            val outputAppender = outputQueue.acquireAppender()
            while (!stop) {
                inputTailer.readingDocument().use { dc ->
                    if (dc.isPresent) {
                        dc.wire()?.read()?.bytes { bytes ->
                            val order = OrderOuterClass.Order.parseFrom(bytes.toByteArray())
                            // TODO - process order
                            outputAppender.writingDocument().use {
                                it.wire()!!.write().bytes(
                                    sequencerResponse {
                                        guid = order.guid
                                        disposition = OrderOuterClass.OrderResponse.OrderDisposition.Accepted
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
