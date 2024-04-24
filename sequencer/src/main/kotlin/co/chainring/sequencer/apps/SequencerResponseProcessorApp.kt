package co.chainring.sequencer.apps

import co.chainring.apps.BaseApp
import co.chainring.core.db.DbConfig
import co.chainring.sequencer.apps.services.SequencerResponseProcessorService
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.concurrent.thread

val dbConfig: DbConfig = DbConfig()

class QueueProcessorApp(
    val inputQueue: RollingChronicleQueue,
    val outputQueue: RollingChronicleQueue,
) : BaseApp(dbConfig) {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var processorThread: Thread

    override fun start() {
        super.start()
        logger.info { "Starting Queue Processor" }
        stop = false

        processorThread = thread(start = false, name = "queue_processor", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("queue_processor")
            val outputTailer = outputQueue.createTailer("queue_processor")

            val lastIndex = SequencerResponseProcessorService.getLastProcessedIndex()?.let {
                minOf(it, outputQueue.lastIndex())
            } ?: outputQueue.lastIndex()
            outputTailer.moveToIndex(lastIndex + 1)
            logger.debug { "Moving index to ${lastIndex + 1}" }

            while (!stop) {
                var response: SequencerResponse? = null
                outputTailer.readingDocument().use {
                    if (it.isPresent) {
                        it.wire()?.read()?.bytes { bytes ->
                            response = SequencerResponse.parseFrom(bytes.toByteArray())
                        }
                    }
                }

                response?.let { resp ->
                    inputTailer.moveToIndex(resp.sequence)
                    inputTailer.readingDocument().use { dc ->
                        if (dc.isPresent) {
                            dc.wire()?.read()?.bytes { bytes ->
                                val lastReadIndex = outputTailer.lastReadIndex()
                                try {
                                    processResponse(bytes.toByteArray(), resp, lastReadIndex)
                                } catch (t: Throwable) {
                                    logger.error(t) { "unhandled exception processing ${resp.sequence} - failed after max retries - stopping" }
                                    stop = true
                                }
                            }
                        }
                    }
                }
            }
            logger.info { "Processor thread stopped" }
        }
        processorThread.uncaughtExceptionHandler = UncaughtExceptionHandler { _, throwable ->
            logger.error(throwable) { "Error in processor main thread" }
            stop()
        }

        processorThread.start()

        logger.info { "Started" }
    }

    private fun processResponse(requestBytes: ByteArray, response: SequencerResponse, lastReadIndex: Long, retryCount: Int = 0) {
        try {
            transaction {
                val request = SequencerRequest.parseFrom(requestBytes)
                logger.debug {
                    "${if (retryCount > 0) {
                        "Retry $retryCount -"
                    } else {
                        ""
                    }}Processing sequence ${response.sequence} request = <$request> response=<$response>"
                }

                SequencerResponseProcessorService.onSequencerResponseReceived(
                    response,
                    request,
                )
                logger.debug { "storing last processed index $lastReadIndex}" }
                SequencerResponseProcessorService.updateLastProcessedIndex(lastReadIndex)
            }
        } catch (t: Throwable) {
            logger.error(t) { "Failed processing response" }
            if (retryCount < 4) {
                Thread.sleep(100)
                processResponse(requestBytes, response, lastReadIndex, retryCount + 1)
            } else {
                throw t
            }
        }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stop = true
        processorThread.join(100)
        processorThread.stop()
        logger.info { "Stopped" }
    }
}
