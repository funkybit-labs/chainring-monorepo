package co.chainring.sequencer.apps

import co.chainring.sequencer.core.inputQueue
import co.chainring.sequencer.core.outputQueue
import co.chainring.sequencer.core.sequencedQueue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderResponse
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.orderResponse
import co.chainring.sequencer.proto.sequenced
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import net.openhft.chronicle.queue.ExcerptTailer
import kotlin.concurrent.getOrSet
import kotlin.system.measureNanoTime

data class GatewayConfig(val port: Int = 5337)

class GatewayApp(private val config: GatewayConfig = GatewayConfig()) : BaseApp() {
    override val logger = KotlinLogging.logger {}

    private val server: Server =
        ServerBuilder
            .forPort(config.port)
            .addService(GatewayService())
            .build()

    override fun start() {
        server.start()
        logger.info { "gRPC server started, listening on ${this.config.port}" }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info { "Shutting down gRPC server since JVM is shutting down" }
                this@GatewayApp.stop()
                logger.info { "gRPC server shut down" }
            },
        )
    }

    override fun stop() {
        logger.info { "Shutting down Gateway App" }
        server.shutdown()
        logger.info { "Gateway App shut down" }
    }

    fun blockUntilShutdown() {
        logger.info { "Blocking until gRPC server shuts down" }
        server.awaitTermination()
    }

    internal class GatewayService() : GatewayGrpcKt.GatewayCoroutineImplBase() {
        private val outputTailer = ThreadLocal<ExcerptTailer>()
        private val logger = KotlinLogging.logger {}

        override suspend fun addOrder(request: Order): OrderResponse {
            var index: Long = 0
            val inputAppender = inputQueue.acquireAppender()
            val sequencedAppender = sequencedQueue.acquireAppender()
            val localTailer = outputTailer.getOrSet { outputQueue.createTailer().toEnd() }
            var disposition: OrderResponse.OrderDisposition? = null
            val processingTime = measureNanoTime {
                try {
                    inputAppender.writingDocument().use {
                        it.wire()?.write()?.bytes(request.toByteArray())
                        it.close()
                        index = inputAppender.lastIndexAppended()
                    }
                    sequencedAppender.writingDocument().use {
                        it.wire()?.write()?.bytes(
                            sequenced {
                                guid = request.guid
                                this.index = index
                            }.toByteArray(),
                        )
                    }
                    while (disposition == null) {
                        localTailer.readingDocument().use {
                            if (it.isPresent) {
                                it.wire()?.read()?.bytes { bytes ->
                                    val response = SequencerResponse.parseFrom(bytes.toByteArray())
                                    if (response.guid == request.guid) {
                                        disposition = response.disposition
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logger.error(e) { "Could not process transaction" }
                }
            }
            return orderResponse {
                guid = request.guid
                this.disposition = disposition ?: OrderResponse.OrderDisposition.Failed
                sequence = index
                this.processingTime = processingTime
            }
        }
    }
}
