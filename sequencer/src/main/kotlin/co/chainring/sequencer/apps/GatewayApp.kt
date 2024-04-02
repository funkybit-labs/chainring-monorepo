package co.chainring.sequencer.apps

import co.chainring.sequencer.core.inputQueue
import co.chainring.sequencer.core.outputQueue
import co.chainring.sequencer.core.sequencedQueue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.GatewayResponse
import co.chainring.sequencer.proto.Market
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerRequestKt
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.gatewayResponse
import co.chainring.sequencer.proto.sequenced
import co.chainring.sequencer.proto.sequencerRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import net.openhft.chronicle.queue.ExcerptTailer
import java.util.UUID
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

        private fun toSequencer(requestGuid: String, requestBuilder: SequencerRequestKt.Dsl.() -> Unit): GatewayResponse {
            var index: Long
            val inputAppender = inputQueue.acquireAppender()
            val sequencedAppender = sequencedQueue.acquireAppender()
            val localTailer = outputTailer.getOrSet { outputQueue.createTailer().toEnd() }
            var sequencerResponse: SequencerResponse? = null
            val processingTime = measureNanoTime {
                val guid = UUID.randomUUID().toString()
                try {
                    inputAppender.writingDocument().use {
                        it.wire()?.write()?.bytes(
                            sequencerRequest(requestBuilder).toByteArray(),
                        )
                        it.close()
                        index = inputAppender.lastIndexAppended()
                    }
                    sequencedAppender.writingDocument().use {
                        it.wire()?.write()?.bytes(
                            sequenced {
                                this.guid = guid
                                this.index = index
                            }.toByteArray(),
                        )
                    }
                    while (sequencerResponse == null) {
                        localTailer.readingDocument().use {
                            if (it.isPresent) {
                                it.wire()?.read()?.bytes { bytes ->
                                    val response = SequencerResponse.parseFrom(bytes.toByteArray())
                                    if (response.guid == requestGuid) {
                                        sequencerResponse = response
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logger.error(e) { "Could not process transaction" }
                }
            }
            return gatewayResponse {
                this.processingTime = processingTime
                sequencerResponse?.let {
                    this.success = true
                    this.sequencerResponse = it
                } ?: run {
                    this.success = false
                }
            }
        }

        override suspend fun addMarket(request: Market): GatewayResponse {
            return toSequencer(request.guid) {
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = request
            }
        }

        override suspend fun applyOrderBatch(request: OrderBatch): GatewayResponse {
            return toSequencer(request.guid) {
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = request
            }
        }
    }
}
