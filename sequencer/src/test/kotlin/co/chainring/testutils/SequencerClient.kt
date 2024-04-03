package co.chainring.testutils

import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.sequencerRequest
import org.junit.jupiter.api.Assertions
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

class SequencerClient {
    private val sequencer = SequencerApp()
    fun addOrder(
        marketId: String,
        amount: Long,
        price: String?,
        wallet: Long,
        orderType: Order.Type,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = marketId
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = amount.toBigInteger().toIntegerValue()
                            this.price = price?.toBigDecimal()?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
                            this.wallet = wallet
                            this.type = orderType
                        },
                    )
                }
            },
        )

    fun createMarket(marketId: String, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal = "17.525".toBigDecimal()) {
        val createMarketResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxLevels = 1000
                    this.maxOrdersPerLevel = 1000
                    this.marketPrice = marketPrice.toDecimalValue()
                }
            },
        )
        Assertions.assertEquals(1, createMarketResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.marketsCreatedList.first()
        Assertions.assertEquals(marketId, createdMarket.marketId)
        Assertions.assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
    }
}