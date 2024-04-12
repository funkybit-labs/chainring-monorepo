package co.chainring.core.sequencer

import co.chainring.core.evm.ECHelper
import co.chainring.core.model.Address
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.sequencerResponse
import co.chainring.sequencer.proto.withdrawal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ManagedChannelBuilder
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

fun OrderId.toSequencerId(): SequencerOrderId {
    return SequencerOrderId(this.value.toSequencerId())
}

fun String.toSequencerId(): Long {
    return BigInteger(1, ECHelper.sha3(this.toByteArray())).toLong()
}

fun Long.sequencerOrderId(): SequencerOrderId {
    return SequencerOrderId(this)
}

fun Address.toSequencerId(): SequencerWalletId {
    return SequencerWalletId(BigInteger(1, ECHelper.sha3(this.value.toByteArray())).toLong())
}

object SequencerClient {

    private val logger = KotlinLogging.logger {}

    data class Order(
        val sequencerOrderId: Long,
        val amount: BigInteger,
        val price: String?,
        val wallet: Long,
        val orderType: co.chainring.sequencer.proto.Order.Type,
    )

    private val channel = ManagedChannelBuilder.forAddress(
        System.getenv("SEQUENCER_HOST_NAME") ?: "localhost",
        (System.getenv("SEQUENCER_PORT") ?: "5337").toInt(),
    ).usePlaintext().build()
    private val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

    suspend fun addOrder(
        marketId: MarketId,
        addOrder: Order,
    ): SequencerResponse {
        return orderBatch(marketId, listOf(addOrder), emptyList(), emptyList())
    }

    suspend fun changeOrder(
        marketId: MarketId,
        changeOrder: Order,
    ): SequencerResponse {
        return orderBatch(marketId, emptyList(), listOf(changeOrder), emptyList())
    }

    suspend fun orderBatch(
        marketId: MarketId,
        ordersToAdd: List<Order>,
        ordersToChange: List<Order>,
        ordersToCancel: List<Long>,
    ): SequencerResponse {
        return stub.applyOrderBatch(
            orderBatch {
                this.marketId = marketId.value
                this.ordersToAdd.addAll(
                    ordersToAdd.map { toOrderDSL(it) },
                )
                this.ordersToChange.addAll(
                    ordersToChange.map { toOrderDSL(it) },
                )
                this.ordersToCancel.addAll(ordersToCancel)
            },
        ).sequencerResponse.also {
            logger.debug { it }
        }
    }

    suspend fun createMarket(marketId: String, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal, baseDecimals: Int, quoteDecimals: Int) {
        stub.addMarket(
            market {
                this.guid = UUID.randomUUID().toString()
                this.marketId = marketId
                this.tickSize = tickSize.toDecimalValue()
                this.maxLevels = 1000
                this.maxOrdersPerLevel = 1000
                this.marketPrice = marketPrice.toDecimalValue()
                this.baseDecimals = baseDecimals
                this.quoteDecimals = quoteDecimals
            },
        )
    }

    suspend fun deposit(
        wallet: Long,
        asset: Asset,
        amount: BigInteger,
    ): SequencerResponse {
        return stub.applyBalanceBatch(
            balanceBatch {
                this.guid = UUID.randomUUID().toString()
                this.deposits.add(
                    deposit {
                        this.asset = asset.value
                        this.wallet = wallet
                        this.amount = amount.toIntegerValue()
                    },
                )
            },
        ).sequencerResponse
    }

    suspend fun withdraw(
        wallet: Long,
        asset: Asset,
        amount: BigInteger,
    ): SequencerResponse {
        return stub.applyBalanceBatch(
            balanceBatch {
                this.guid = UUID.randomUUID().toString()
                this.withdrawals.add(
                    withdrawal {
                        this.asset = asset.value
                        this.wallet = wallet
                        this.amount = amount.toIntegerValue()
                    },
                )
            },
        ).sequencerResponse
    }

    suspend fun cancelOrder(
        marketId: MarketId,
        sequencerOrderId: Long,
    ): SequencerResponse {
        return orderBatch(marketId, emptyList(), emptyList(), listOf(sequencerOrderId))
    }

    suspend fun cancelOrders(
        marketId: MarketId,
        sequencerOrderIds: List<Long>,
    ): SequencerResponse {
        return orderBatch(marketId, emptyList(), emptyList(), sequencerOrderIds)
    }

    private fun toOrderDSL(order: Order) = order {
        this.guid = order.sequencerOrderId
        this.amount = order.amount.toIntegerValue()
        this.price = order.price?.toBigDecimal()?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
        this.wallet = order.wallet
        this.type = order.orderType
    }
}
