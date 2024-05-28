package co.chainring.sequencer.apps

import co.chainring.apps.BaseApp
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.KeyValueStore
import co.chainring.sequencer.apps.services.SequencerResponseProcessorService
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import net.openhft.chronicle.queue.impl.RollingChronicleQueue
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

val dbConfig: DbConfig = DbConfig()

class SequencerResponseProcessorApp(
    val inputQueue: RollingChronicleQueue,
    val outputQueue: RollingChronicleQueue,
    val onAbnormalStop: () -> Unit,
) : BaseApp(dbConfig) {
    override val logger = KotlinLogging.logger {}
    private var stop = false
    private lateinit var processorThread: Thread

    override fun start() {
        super.start()
        logger.info { "Starting Sequencer Response Processor" }
        stop = false

        val lastIndex = transaction {
            getLastProcessedIndex()
                ?.let { minOf(it, outputQueue.lastIndex()) }
                ?: outputQueue.lastIndex()
        }

        processorThread = thread(start = false, name = "sequencer_response_processor", isDaemon = false) {
            val inputTailer = inputQueue.createTailer("queue_processor")
            val outputTailer = outputQueue.createTailer("queue_processor")

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
                                processResponseWithRetries(bytes.toByteArray(), resp, lastReadIndex)
                            }
                        }
                    }
                }
            }
            logger.info { "Processor thread stopped" }
        }
        processorThread.uncaughtExceptionHandler = UncaughtExceptionHandler { _, throwable ->
            logger.error(throwable) { "Unhandled error in processor main thread" }
            onAbnormalStop()
        }

        processorThread.start()

        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }
        stop = true
        processorThread.join(100)
        processorThread.stop()
        logger.info { "Stopped" }
    }

    private fun processResponseWithRetries(requestBytes: ByteArray, response: SequencerResponse, lastReadIndex: Long) {
        val startedAt = Clock.System.now()
        val alertAfterDuration = 1.minutes
        var attempt = 0L
        var notified = false

        while (true) {
            runCatching {
                attempt += 1
                transaction {
                    val request = SequencerRequest.parseFrom(requestBytes)

                    logger.debug { "Processing sequence ${response.sequence}: attempt=$attempt, request=<$request>, response=<$response>" }
                    SequencerResponseProcessorService.processResponse(response, request)

                    logger.debug { "Storing last processed index $lastReadIndex" }
                    updateLastProcessedIndex(lastReadIndex)
                }
            }.onSuccess {
                return
            }.onFailure { error ->
                Thread.sleep(100)
                val timeSinceStarted = Clock.System.now() - startedAt
                if (timeSinceStarted > alertAfterDuration && !notified) {
                    logger.error(error) { "Can't process sequencer response after retrying for $timeSinceStarted" }
                    notified = true
                }
            }
        }
    }

    private val lastProcessedOutputIndexKey = "LastProcessedOutputIndex"

    private fun getLastProcessedIndex(): Long? =
        KeyValueStore.getLong(lastProcessedOutputIndexKey)

    private fun updateLastProcessedIndex(lastProcessedIndex: Long) =
        KeyValueStore.setLong(lastProcessedOutputIndexKey, lastProcessedIndex)
}
