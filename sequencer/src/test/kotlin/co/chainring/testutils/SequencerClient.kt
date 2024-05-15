package co.chainring.testutils

import co.chainring.core.model.db.FeeRates
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.OrderGuid
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toMarketId
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.sequencerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertNotNull

class SequencerClient {
    private val sequencer = SequencerApp(checkpointsPath = null)

    fun addOrder(
        marketId: MarketId,
        amount: BigInteger,
        price: String?,
        wallet: WalletAddress,
        orderType: Order.Type,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = marketId.value
                    this.wallet = wallet.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = amount.toIntegerValue()
                            this.price = price?.toBigDecimal()?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
                            this.type = orderType
                        },
                    )
                }
            },
        )

    fun changeOrder(
        marketId: MarketId,
        guid: OrderGuid,
        amount: Long,
        price: String,
        wallet: WalletAddress,
    ) = sequencer.processRequest(
        sequencerRequest {
            this.guid = UUID.randomUUID().toString()
            this.type = SequencerRequest.Type.ApplyOrderBatch
            this.orderBatch = orderBatch {
                this.marketId = marketId.value
                this.wallet = wallet.value
                this.ordersToChange.add(
                    order {
                        this.guid = guid.value
                        this.amount = amount.toBigInteger().toIntegerValue()
                        this.price = price.toBigDecimal().toDecimalValue()
                    },
                )
            }
        },
    )

    fun cancelOrder(marketId: MarketId, guid: Long, wallet: WalletAddress) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = marketId.value
                    this.wallet = wallet.value
                    this.ordersToCancel.add(
                        co.chainring.sequencer.proto.cancelOrder {
                            this.guid = guid
                            this.externalGuid = ""
                        },
                    )
                }
            },
        )

    fun createMarket(marketId: MarketId, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal = "17.525".toBigDecimal(), baseDecimals: Int = 8, quoteDecimals: Int = 18) {
        val createMarketResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxLevels = 1000
                    this.maxOrdersPerLevel = 1000
                    this.marketPrice = marketPrice.toDecimalValue()
                    this.baseDecimals = baseDecimals
                    this.quoteDecimals = quoteDecimals
                }
            },
        )
        assertEquals(1, createMarketResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.marketsCreatedList.first()
        assertEquals(marketId, createdMarket.marketId.toMarketId())
        assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
    }

    fun setFeeRates(feeRates: FeeRates) {
        val response = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.SetFeeRates
                this.feeRates = feeRates {
                    this.maker = feeRates.maker.value
                    this.taker = feeRates.taker.value
                }
            },
        )
        val feeRatesSet = response.feeRatesSet
        assertNotNull(feeRatesSet)
        assertEquals(feeRates.maker.value, feeRatesSet.maker)
        assertEquals(feeRates.taker.value, feeRatesSet.taker)
    }

    private fun List<BigInteger>.sum() = this.reduce { a, b -> a + b }

    fun depositsAndWithdrawals(walletAddress: WalletAddress, asset: Asset, amounts: List<BigInteger>, expectedAmount: BigInteger? = amounts.sum()): SequencerResponse {
        val depositsAndWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    amounts.filter { a -> a > BigInteger.ZERO }.let { deposits ->
                        this.deposits.addAll(
                            deposits.map {
                                co.chainring.sequencer.proto.deposit {
                                    this.asset = asset.value
                                    this.wallet = walletAddress.value
                                    this.amount = it.toIntegerValue()
                                }
                            },
                        )
                    }
                    amounts.filter { a -> a < BigInteger.ZERO }.let { withdrawals ->
                        this.withdrawals.addAll(
                            withdrawals.map {
                                co.chainring.sequencer.proto.withdrawal {
                                    this.asset = asset.value
                                    this.wallet = walletAddress.value
                                    this.amount = (-it).toIntegerValue()
                                }
                            },
                        )
                    }
                }
            },
        )
        if (expectedAmount != null) {
            assertEquals(1, depositsAndWithdrawalsResponse.balancesChangedCount)
            val withdrawal = depositsAndWithdrawalsResponse.balancesChangedList.first()
            assertEquals(asset.value, withdrawal.asset)
            assertEquals(walletAddress.value, withdrawal.wallet)
            assertEquals(expectedAmount, withdrawal.delta.toBigInteger())
        } else {
            assertEquals(0, depositsAndWithdrawalsResponse.balancesChangedCount)
        }
        return depositsAndWithdrawalsResponse
    }

    fun deposit(walletAddress: WalletAddress, asset: Asset, amount: BigInteger) = depositsAndWithdrawals(walletAddress, asset, listOf(amount))
    fun withdrawal(walletAddress: WalletAddress, asset: Asset, amount: BigInteger, expectedAmount: BigInteger? = amount) = depositsAndWithdrawals(walletAddress, asset, listOf(-amount), expectedAmount?.negate())

    fun failedWithdrawals(walletAddress: WalletAddress, asset: Asset, amounts: List<BigInteger>, expectedAmount: BigInteger? = amounts.sum()): SequencerResponse {
        val failedWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedWithdrawals.addAll(
                        amounts.map {
                            co.chainring.sequencer.proto.failedWithdrawal {
                                this.asset = asset.value
                                this.wallet = walletAddress.value
                                this.amount = it.toIntegerValue()
                            }
                        },
                    )
                }
            },
        )
        if (expectedAmount != null) {
            assertEquals(1, failedWithdrawalsResponse.balancesChangedCount)
            val withdrawal = failedWithdrawalsResponse.balancesChangedList.first()
            assertEquals(asset.value, withdrawal.asset)
            assertEquals(walletAddress.value, withdrawal.wallet)
            assertEquals(expectedAmount, withdrawal.delta.toBigInteger())
        } else {
            assertEquals(0, failedWithdrawalsResponse.balancesChangedCount)
        }
        return failedWithdrawalsResponse
    }

    fun failedSettlement(buyWalletAddress: WalletAddress, sellWalletAddress: WalletAddress, marketId: MarketId, trade: TradeCreated): SequencerResponse {
        val failedSettlementsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedSettlements.add(
                        co.chainring.sequencer.proto.failedSettlement {
                            this.buyWallet = buyWalletAddress.value
                            this.sellWallet = sellWalletAddress.value
                            this.marketId = marketId.value
                            this.trade = trade
                        },
                    )
                }
            },
        )
        return failedSettlementsResponse
    }
}
