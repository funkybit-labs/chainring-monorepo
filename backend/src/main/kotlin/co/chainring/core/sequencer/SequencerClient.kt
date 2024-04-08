package co.chainring.core.sequencer

import co.chainring.core.evm.ECHelper
import co.chainring.core.model.Address
import co.chainring.core.model.db.OrderId
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.withdrawal
import io.grpc.ManagedChannelBuilder
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

fun OrderId.toSequencerId(): Long {
    return this.value.toSequencerId()
}

fun String.toSequencerId(): Long {
    return BigInteger(1, ECHelper.sha3(this.toByteArray())).toLong()
}

fun Address.toSequencerId(): Long {
    return BigInteger(1, ECHelper.sha3(this.value.toByteArray())).toLong()
}

object SequencerClient {
    private val channel = ManagedChannelBuilder.forAddress(
        System.getenv("SEQUENCER_HOST_NAME") ?: "localhost",
        (System.getenv("SEQUENCER_PORT") ?: "5337").toInt(),
    ).usePlaintext().build()
    private val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

    suspend fun addOrder(
        sequencerOrderId: Long,
        marketId: String,
        amount: BigInteger,
        price: String?,
        wallet: Long,
        orderType: Order.Type,
    ): SequencerResponse {
        return stub.applyOrderBatch(
            orderBatch {
                this.marketId = marketId
                this.ordersToAdd.add(
                    order {
                        this.guid = sequencerOrderId
                        this.amount = amount.toIntegerValue()
                        this.price = price?.toBigDecimal()?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
                        this.wallet = wallet
                        this.type = orderType
                    },
                )
            },
        ).sequencerResponse
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

    suspend fun withdrawal(
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
}
