package xyz.funkybit.sequencer.apps

import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import net.openhft.chronicle.queue.ChronicleQueue
import net.openhft.chronicle.queue.ExcerptTailer
import xyz.funkybit.sequencer.proto.AuthorizeWalletRequest
import xyz.funkybit.sequencer.proto.BackToBackOrderRequest
import xyz.funkybit.sequencer.proto.BalanceBatch
import xyz.funkybit.sequencer.proto.GatewayGrpcKt
import xyz.funkybit.sequencer.proto.GatewayResponse
import xyz.funkybit.sequencer.proto.GetStateRequest
import xyz.funkybit.sequencer.proto.Market
import xyz.funkybit.sequencer.proto.OrderBatch
import xyz.funkybit.sequencer.proto.ResetRequest
import xyz.funkybit.sequencer.proto.SequencerRequest
import xyz.funkybit.sequencer.proto.SequencerRequestKt
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.SetFeeRatesRequest
import xyz.funkybit.sequencer.proto.SetMarketMinFeesRequest
import xyz.funkybit.sequencer.proto.SetWithdrawalFeesRequest
import xyz.funkybit.sequencer.proto.gatewayResponse
import xyz.funkybit.sequencer.proto.sequenced
import xyz.funkybit.sequencer.proto.sequencerRequest
import java.util.UUID
import kotlin.concurrent.getOrSet
import kotlin.system.measureNanoTime
import xyz.funkybit.sequencer.core.inputQueue as defaultInputQueue
import xyz.funkybit.sequencer.core.outputQueue as defaultOutputQueue
import xyz.funkybit.sequencer.core.sequencedQueue as defaultSequencedQueue

data class GatewayConfig(val port: Int = 5337)

class GatewayApp(
    private val config: GatewayConfig = GatewayConfig(),
    inputQueue: ChronicleQueue = defaultInputQueue,
    outputQueue: ChronicleQueue = defaultOutputQueue,
    sequencedQueue: ChronicleQueue = defaultSequencedQueue,
) : BaseApp() {
    override val logger = KotlinLogging.logger {}

    private val server: Server =
        ServerBuilder
            .forPort(config.port)
            .addService(GatewayService(inputQueue, outputQueue, sequencedQueue))
            .build()

    override fun start() {
        logger.info { "Starting" }
        server.start()
        logger.info { "gRPC server started, listening on ${this.config.port}" }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info { "Shutting down gRPC server since JVM is shutting down" }
                this@GatewayApp.stop()
                logger.info { "gRPC server shut down" }
            },
        )
        logger.info { "Started" }
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

    internal class GatewayService(
        private val inputQueue: ChronicleQueue,
        private val outputQueue: ChronicleQueue,
        private val sequencedQueue: ChronicleQueue,
    ) : GatewayGrpcKt.GatewayCoroutineImplBase() {
        private val outputTailer = ThreadLocal<ExcerptTailer>()
        private val logger = KotlinLogging.logger {}

        private fun toSequencer(requestBuilder: SequencerRequestKt.Dsl.() -> Unit): GatewayResponse {
            var index: Long
            val inputAppender = inputQueue.acquireAppender()
            val sequencedAppender = sequencedQueue.acquireAppender()
            val localTailer = outputTailer.getOrSet { outputQueue.createTailer().toEnd() }
            val sequencerRequest = sequencerRequest(requestBuilder)
            var sequencerResponse: SequencerResponse? = null
            val processingTime = measureNanoTime {
                val guid = UUID.randomUUID().toString()
                try {
                    inputAppender.writingDocument().use {
                        it.wire()?.write()?.bytes(
                            sequencerRequest.toByteArray(),
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
                                    if (response.guid == sequencerRequest.guid) {
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
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = request
            }
        }

        override suspend fun applyOrderBatch(request: OrderBatch): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = request
            }
        }

        override suspend fun applyBalanceBatch(request: BalanceBatch): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = request
            }
        }

        override suspend fun reset(request: ResetRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.Reset
            }
        }

        override suspend fun getState(request: GetStateRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.GetState
            }
        }

        override suspend fun setFeeRates(request: SetFeeRatesRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.SetFeeRates
                this.feeRates = request.feeRates
            }
        }

        override suspend fun setWithdrawalFees(request: SetWithdrawalFeesRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.SetWithdrawalFees
                this.withdrawalFees.addAll(request.withdrawalFeesList)
            }
        }

        override suspend fun setMarketMinFees(request: SetMarketMinFeesRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.SetMarketMinFees
                this.marketMinFees.addAll(request.marketMinFeesList)
            }
        }

        override suspend fun applyBackToBackOrder(request: BackToBackOrderRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.ApplyBackToBackOrder
                this.backToBackOrder = request.order
            }
        }

        override suspend fun authorizeWallet(request: AuthorizeWalletRequest): GatewayResponse {
            return toSequencer {
                this.guid = request.guid
                this.type = SequencerRequest.Type.AuthorizeWallet
                this.authorizeWallet = request.authorization
            }
        }
    }
}
