package xyz.funkybit.core.sequencer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import xyz.funkybit.apps.api.middleware.ServerSpans
import xyz.funkybit.apps.api.middleware.Tracer
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.SequencerAccountId
import xyz.funkybit.core.model.SequencerOrderId
import xyz.funkybit.core.model.SequencerWalletId
import xyz.funkybit.core.model.WithdrawalFee
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ClientOrderId
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.core.toDecimalValue
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.proto.GatewayGrpcKt
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.backToBackOrder
import xyz.funkybit.sequencer.proto.backToBackOrderRequest
import xyz.funkybit.sequencer.proto.balanceBatch
import xyz.funkybit.sequencer.proto.cancelOrder
import xyz.funkybit.sequencer.proto.deposit
import xyz.funkybit.sequencer.proto.failedWithdrawal
import xyz.funkybit.sequencer.proto.feeRates
import xyz.funkybit.sequencer.proto.getStateRequest
import xyz.funkybit.sequencer.proto.market
import xyz.funkybit.sequencer.proto.order
import xyz.funkybit.sequencer.proto.orderBatch
import xyz.funkybit.sequencer.proto.resetRequest
import xyz.funkybit.sequencer.proto.setFeeRatesRequest
import xyz.funkybit.sequencer.proto.setMarketMinFeesRequest
import xyz.funkybit.sequencer.proto.setWithdrawalFeesRequest
import xyz.funkybit.sequencer.proto.withdrawal
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

fun OrderId.toSequencerId(): SequencerOrderId {
    return this.value.hashToLong().toSequencerOrderId()
}

fun String.hashToLong(): Long {
    return BigInteger(1, ECHelper.sha3(this.toByteArray())).toLong()
}
fun String.toOrderId(): OrderId {
    return OrderId(this)
}

fun String.toClientOrderId(): ClientOrderId {
    return ClientOrderId(this)
}

fun String.toWithdrawalId(): WithdrawalId {
    return WithdrawalId(this)
}

fun String.toDepositId(): DepositId {
    return DepositId(this)
}

fun Long.toSequencerOrderId(): SequencerOrderId {
    return SequencerOrderId(this)
}

fun Address.toSequencerId(): SequencerWalletId {
    return SequencerWalletId(
        BigInteger(
            1,
            ECHelper.sha3(
                when (this) {
                    is EvmAddress -> this.value.toHexBytes()
                    is BitcoinAddress -> this.script().toByteArray()
                },
            ),
        ).toLong(),
    )
}

fun UserId.toSequencerId(): SequencerAccountId {
    return SequencerAccountId(
        BigInteger(
            1,
            ECHelper.sha3(value.toByteArray()),
        ).toLong(),
    )
}

fun Long.toSequencerAccountId(): SequencerAccountId {
    return SequencerAccountId(this)
}

fun EvmAddress.toSequencerId(): SequencerWalletId {
    return SequencerWalletId(BigInteger(1, ECHelper.sha3(this.value.toHexBytes())).toLong())
}

fun Long.toSequencerWalletId(): SequencerWalletId {
    return SequencerWalletId(this)
}

open class SequencerClient {
    private val logger = KotlinLogging.logger {}

    data class Order(
        val sequencerOrderId: Long,
        val amount: BigInteger,
        val levelIx: Int?,
        val orderType: xyz.funkybit.sequencer.proto.Order.Type,
        val nonce: BigInteger?,
        val signature: EvmSignature?,
        val orderId: OrderId,
        val clientOrderId: ClientOrderId?,
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
        account: SequencerAccountId,
        walletId: SequencerWalletId,
        ordersToAdd: List<Order>,
        ordersToCancel: List<OrderId>,
        cancelAll: Boolean = false,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.account = account.value
                    this.wallet = walletId.value
                    this.ordersToAdd.addAll(
                        ordersToAdd.map { toOrderDSL(it) },
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
        account: SequencerAccountId,
        walletId: SequencerWalletId,
        order: Order,
    ): SequencerResponse {
        return Tracer.newCoroutineSpan(ServerSpans.sqrClt) {
            stub.applyBackToBackOrder(
                backToBackOrderRequest {
                    this.guid = UUID.randomUUID().toString()
                    this.order = backToBackOrder {
                        this.account = account.value
                        this.wallet = walletId.value
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
                            xyz.funkybit.sequencer.proto.withdrawalFee {
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
                            xyz.funkybit.sequencer.proto.marketMinFee {
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
        account: SequencerAccountId,
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
                            this.account = account.value
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
        account: SequencerAccountId,
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
                            this.account = account.value
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
        account: SequencerAccountId,
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
                            this.account = account.value
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
        buyerAccount: SequencerAccountId,
        sellerAccount: SequencerAccountId,
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
                        xyz.funkybit.sequencer.proto.failedSettlement {
                            this.buyAccount = buyerAccount.value
                            this.sellAccount = sellerAccount.value
                            this.marketId = marketId.value
                            this.trade = xyz.funkybit.sequencer.proto.tradeCreated {
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
        account: SequencerAccountId,
        walletId: SequencerWalletId,
        orderId: OrderId,
    ): SequencerResponse {
        return orderBatch(marketId, account, walletId, emptyList(), listOf(orderId))
    }

    suspend fun cancelOrders(
        marketId: MarketId,
        account: SequencerAccountId,
        walletId: SequencerWalletId,
        orderIds: List<OrderId>,
        cancelAll: Boolean = false,
    ): SequencerResponse {
        return orderBatch(marketId, account, walletId, emptyList(), orderIds, cancelAll = cancelAll)
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
        this.clientOrderGuid = order.clientOrderId?.value ?: ""
    }

    private fun toCancelOrderDSL(orderId: OrderId) = cancelOrder {
        this.guid = orderId.toSequencerId().value
        this.externalGuid = orderId.value
    }
}
