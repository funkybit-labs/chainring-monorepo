package co.chainring.core.sequencer

import co.chainring.apps.api.middleware.ServerSpans
import co.chainring.apps.api.middleware.Tracer
import co.chainring.core.evm.ECHelper
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.WithdrawalFee
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.backToBackOrder
import co.chainring.sequencer.proto.backToBackOrderRequest
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.cancelOrder
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.failedWithdrawal
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.getStateRequest
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.resetRequest
import co.chainring.sequencer.proto.setFeeRatesRequest
import co.chainring.sequencer.proto.setMarketMinFeesRequest
import co.chainring.sequencer.proto.setWithdrawalFeesRequest
import co.chainring.sequencer.proto.withdrawal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

fun OrderId.toSequencerId(): SequencerOrderId {
    return this.value.hashToLong().sequencerOrderId()
}

fun String.hashToLong(): Long {
    return BigInteger(1, ECHelper.sha3(this.toByteArray())).toLong()
}
fun String.orderId(): OrderId {
    return OrderId(this)
}

fun String.withdrawalId(): WithdrawalId {
    return WithdrawalId(this)
}

fun String.depositId(): DepositId {
    return DepositId(this)
}

fun Long.sequencerOrderId(): SequencerOrderId {
    return SequencerOrderId(this)
}

fun Address.toSequencerId(): SequencerWalletId {
    return SequencerWalletId(BigInteger(1, ECHelper.sha3(this.value.toHexBytes())).toLong())
}

fun Long.sequencerWalletId(): SequencerWalletId {
    return SequencerWalletId(this)
}

open class SequencerClient {
    private val logger = KotlinLogging.logger {}

    data class Order(
        val sequencerOrderId: Long,
        val amount: BigInteger,
        val levelIx: Int?,
        val orderType: co.chainring.sequencer.proto.Order.Type,
        val nonce: BigInteger?,
        val signature: EvmSignature?,
        val orderId: OrderId,
        val chainId: ChainId,
        val percentage: Int?,
    )

    protected val channel: ManagedChannel = ManagedChannelBuilder.forAddress(
        System.getenv("SEQUENCER_HOST_NAME") ?: "localhost",
        (System.getenv("SEQUENCER_PORT") ?: "5337").toInt(),
    ).usePlaintext().build()

    protected val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

    suspend fun orderBatch(
        marketId: MarketId,
        wallet: Long,
        ordersToAdd: List<Order>,
        ordersToChange: List<Order>,
        ordersToCancel: List<OrderId>,
        cancelAll: Boolean = false,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.wallet = wallet
                    this.ordersToAdd.addAll(
                        ordersToAdd.map { toOrderDSL(it) },
                    )
                    this.ordersToChange.addAll(
                        ordersToChange.map { toOrderDSL(it) },
                    )
                    this.ordersToCancel.addAll(
                        ordersToCancel.map { toCancelOrderDSL(it) },
                    )
                    this.cancelAll = cancelAll
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun backToBackOrder(
        marketIds: List<MarketId>,
        wallet: Long,
        order: Order,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBackToBackOrder(
                backToBackOrderRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.order = backToBackOrder {
                        this.wallet = wallet
                        this.marketIds.addAll(marketIds.map { it.value })
                        this.order = toOrderDSL(order)
                    }
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun createMarket(marketId: String, tickSize: BigDecimal = "0.05".toBigDecimal(), baseDecimals: Int, quoteDecimals: Int, minFee: BigDecimal): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.addMarket(
                market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxOrdersPerLevel = 1000
                    this.baseDecimals = baseDecimals
                    this.quoteDecimals = quoteDecimals
                    this.minFee = minFee.toFundamentalUnits(quoteDecimals).toIntegerValue()
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun setFeeRates(feeRates: FeeRates): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.setFeeRates(
                setFeeRatesRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.feeRates = feeRates {
                        this.maker = feeRates.maker.value
                        this.taker = feeRates.taker.value
                    }
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun setWithdrawalFees(withdrawalFees: List<WithdrawalFee>): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.setWithdrawalFees(
                setWithdrawalFeesRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.withdrawalFees.addAll(
                        withdrawalFees.map {
                            co.chainring.sequencer.proto.withdrawalFee {
                                this.asset = it.asset.value
                                this.value = it.fee.toIntegerValue()
                            }
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }
    suspend fun setMarketMinFees(marketMinFees: Map<MarketId, BigInteger>): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.setMarketMinFees(
                setMarketMinFeesRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.marketMinFees.addAll(
                        marketMinFees.map {
                            co.chainring.sequencer.proto.marketMinFee {
                                this.marketId = it.key.value
                                this.minFee = it.value.toIntegerValue()
                            }
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun deposit(
        wallet: Long,
        asset: Asset,
        amount: BigInteger,
        depositId: DepositId,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBalanceBatch(
                balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.deposits.add(
                        deposit {
                            this.asset = asset.value
                            this.wallet = wallet
                            this.amount = amount.toIntegerValue()
                            this.externalGuid = depositId.value
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun withdraw(
        wallet: Long,
        asset: Asset,
        amount: BigInteger,
        nonce: BigInteger,
        evmSignature: EvmSignature,
        withdrawalId: WithdrawalId,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBalanceBatch(
                balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.withdrawals.add(
                        withdrawal {
                            this.asset = asset.value
                            this.wallet = wallet
                            this.amount = amount.toIntegerValue()
                            this.nonce = nonce.toIntegerValue()
                            this.signature = evmSignature.value
                            this.externalGuid = withdrawalId.value
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun failWithdraw(
        wallet: Long,
        asset: Asset,
        amount: BigInteger,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBalanceBatch(
                balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedWithdrawals.add(
                        failedWithdrawal {
                            this.asset = asset.value
                            this.wallet = wallet
                            this.amount = amount.toIntegerValue()
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun failSettlement(
        buyWallet: Long,
        sellWallet: Long,
        marketId: MarketId,
        buyOrderId: OrderId,
        sellOrderId: OrderId,
        amount: BigInteger,
        levelIx: Int,
        buyerFee: BigInteger,
        sellerFee: BigInteger,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBalanceBatch(
                balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedSettlements.add(
                        co.chainring.sequencer.proto.failedSettlement {
                            this.buyWallet = buyWallet
                            this.sellWallet = sellWallet
                            this.marketId = marketId.value
                            this.trade = co.chainring.sequencer.proto.tradeCreated {
                                this.buyOrderGuid = buyOrderId.toSequencerId().value
                                this.sellOrderGuid = sellOrderId.toSequencerId().value
                                this.amount = amount.toIntegerValue()
                                this.levelIx = levelIx
                                this.buyerFee = buyerFee.toIntegerValue()
                                this.sellerFee = sellerFee.toIntegerValue()
                            }
                        },
                    )
                },
            )
        }.also {
            Tracer.newSpan(ServerSpans.gtw, it.processingTime)
            Tracer.newSpan(ServerSpans.sqr, it.sequencerResponse.processingTime)
        }.sequencerResponse
    }

    suspend fun cancelOrder(
        marketId: MarketId,
        wallet: Long,
        orderId: OrderId,
    ): SequencerResponse {
        return orderBatch(marketId, wallet, emptyList(), emptyList(), listOf(orderId))
    }

    suspend fun cancelOrders(
        marketId: MarketId,
        wallet: Long,
        orderIds: List<OrderId>,
        cancelAll: Boolean = false,
    ): SequencerResponse {
        return orderBatch(marketId, wallet, emptyList(), emptyList(), orderIds, cancelAll = cancelAll)
    }

    suspend fun reset(): SequencerResponse {
        return stub.reset(
            resetRequest {
                this.guid = UUID.randomUUID().toString()
            },
        ).sequencerResponse
    }

    suspend fun getState(): SequencerResponse {
        return stub.getState(
            getStateRequest {
                this.guid = UUID.randomUUID().toString()
            },
        ).sequencerResponse
    }

    private fun toOrderDSL(order: Order) = order {
        this.guid = order.sequencerOrderId
        this.amount = order.amount.toIntegerValue()
        this.levelIx = order.levelIx ?: 0
        this.type = order.orderType
        this.nonce = order.nonce?.toIntegerValue() ?: BigInteger.ZERO.toIntegerValue()
        this.signature = order.signature?.value ?: EvmSignature.emptySignature().value
        this.externalGuid = order.orderId.value
        this.chainId = order.chainId.value.toInt()
        this.percentage = order.percentage ?: 0
    }

    private fun toCancelOrderDSL(orderId: OrderId) = cancelOrder {
        this.guid = orderId.toSequencerId().value
        this.externalGuid = orderId.value
    }
}
