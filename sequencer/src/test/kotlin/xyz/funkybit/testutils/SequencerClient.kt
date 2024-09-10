package xyz.funkybit.testutils

import org.junit.jupiter.api.Assertions.assertEquals
import xyz.funkybit.core.model.SequencerUserId
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.utils.sum
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.sequencer.apps.SequencerApp
import xyz.funkybit.sequencer.core.Clock
import xyz.funkybit.sequencer.core.MarketId
import xyz.funkybit.sequencer.core.WalletAddress
import xyz.funkybit.sequencer.core.toBigDecimal
import xyz.funkybit.sequencer.core.toBigInteger
import xyz.funkybit.sequencer.core.toDecimalValue
import xyz.funkybit.sequencer.core.toIntegerValue
import xyz.funkybit.sequencer.core.toMarketId
import xyz.funkybit.sequencer.proto.Order
import xyz.funkybit.sequencer.proto.OrderChanged
import xyz.funkybit.sequencer.proto.OrderDisposition
import xyz.funkybit.sequencer.proto.SequencerRequest
import xyz.funkybit.sequencer.proto.SequencerResponse
import xyz.funkybit.sequencer.proto.TradeCreated
import xyz.funkybit.sequencer.proto.backToBackOrder
import xyz.funkybit.sequencer.proto.balanceBatch
import xyz.funkybit.sequencer.proto.feeRates
import xyz.funkybit.sequencer.proto.market
import xyz.funkybit.sequencer.proto.order
import xyz.funkybit.sequencer.proto.orderBatch
import xyz.funkybit.sequencer.proto.sequencerRequest
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertNotNull

class SequencerClient(clock: Clock) {
    private val sequencer = SequencerApp(clock, checkpointsQueue = null)

    data class Asset(
        val name: String,
        val decimals: Int,
    )

    data class WithdrawalFee(
        val asset: Symbol,
        val fee: BigInteger,
    )

    data class User(
        val account: SequencerUserId,
        val wallet: WalletAddress,
    )

    data class Market(
        val id: MarketId,
        val tickSize: BigDecimal,
        val baseDecimals: Int,
        val quoteDecimals: Int,
    ) {
        val baseAsset: Asset = Asset(id.baseAsset().value, baseDecimals)
        val quoteAsset: Asset = Asset(id.quoteAsset().value, quoteDecimals)

        fun getAsset(name: String): Asset =
            when (name) {
                baseAsset.name -> baseAsset
                quoteAsset.name -> quoteAsset
                else -> throw RuntimeException("Unexpected asset $name for market $this")
            }
    }

    fun addOrder(
        market: Market,
        amount: BigDecimal,
        price: BigDecimal?,
        user: SequencerUserId,
        wallet: WalletAddress,
        orderType: Order.Type,
        percentage: Int = 0,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = market.id.value
                    this.account = user.value
                    this.wallet = wallet.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = amount.toFundamentalUnits(market.baseDecimals).toIntegerValue()
                            this.levelIx = price?.divideToIntegralValue(market.tickSize)?.toInt() ?: 0
                            this.type = orderType
                            this.percentage = percentage
                        },
                    )
                }
            },
        )

    fun addBackToBackOrder(
        orderGuid: Long,
        firstMarket: Market,
        secondMarket: Market,
        amount: BigDecimal,
        user: SequencerUserId,
        wallet: WalletAddress,
        orderType: Order.Type,
        percentage: Int? = null,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBackToBackOrder
                this.backToBackOrder = backToBackOrder {
                    this.marketIds.addAll(listOf(firstMarket.id.value, secondMarket.id.value))
                    this.account = user.value
                    this.wallet = wallet.value
                    this.order = order {
                        this.guid = orderGuid
                        this.amount = amount.toFundamentalUnits(firstMarket.baseDecimals).toIntegerValue()
                        this.type = orderType
                        percentage?.let { this.percentage = percentage }
                    }
                }
            },
        )

    fun addOrderAndVerifyAccepted(
        market: Market,
        amount: BigDecimal,
        price: BigDecimal?,
        user: SequencerUserId,
        wallet: WalletAddress,
        orderType: Order.Type,
    ): OrderChanged =
        addOrder(market, amount, price, user, wallet, orderType).let {
            assertEquals(OrderDisposition.Accepted, it.ordersChangedList.first().disposition)
            it.ordersChangedList.first()
        }

    fun cancelOrder(market: Market, guid: Long, user: SequencerUserId, wallet: WalletAddress) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = market.id.value
                    this.account = user.value
                    this.wallet = wallet.value
                    this.ordersToCancel.add(
                        xyz.funkybit.sequencer.proto.cancelOrder {
                            this.guid = guid
                            this.externalGuid = ""
                        },
                    )
                }
            },
        )

    fun createMarket(marketId: MarketId, tickSize: BigDecimal = "0.05".toBigDecimal(), baseDecimals: Int = 8, quoteDecimals: Int = 18, minFee: BigDecimal? = null): Market {
        val createMarketResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxOrdersPerLevel = 1000
                    this.baseDecimals = baseDecimals
                    this.quoteDecimals = quoteDecimals
                    minFee?.let { this.minFee = minFee.toFundamentalUnits(quoteDecimals).toIntegerValue() }
                }
            },
        )
        assertEquals(1, createMarketResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.marketsCreatedList.first()
        assertEquals(marketId, createdMarket.marketId.toMarketId())
        assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
        assertEquals(baseDecimals, createdMarket.baseDecimals)
        assertEquals(quoteDecimals, createdMarket.quoteDecimals)

        return Market(
            marketId,
            tickSize = createdMarket.tickSize.toBigDecimal(),
            baseDecimals = createdMarket.baseDecimals,
            quoteDecimals = createdMarket.quoteDecimals,
        )
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

    fun setWithdrawalFees(fees: List<WithdrawalFee>) {
        val response = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.SetWithdrawalFees
                this.withdrawalFees.addAll(
                    fees.map {
                        xyz.funkybit.sequencer.proto.withdrawalFee {
                            this.asset = it.asset.value
                            this.value = it.fee.toIntegerValue()
                        }
                    },
                )
            },
        )
        val withdrawalFeesSet = response.withdrawalFeesSetList
        assertEquals(withdrawalFeesSet.size, fees.size)
    }

    fun depositsAndWithdrawals(account: SequencerUserId, asset: Asset, amounts: List<BigDecimal>, expectedAmount: BigDecimal? = amounts.sum(), expectedWithdrawalFees: List<BigInteger> = listOf()): SequencerResponse {
        val depositsAndWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    amounts.filter { a -> a > BigDecimal.ZERO }.let { deposits ->
                        this.deposits.addAll(
                            deposits.map {
                                xyz.funkybit.sequencer.proto.deposit {
                                    this.asset = asset.name
                                    this.account = account.value
                                    this.amount = it.toFundamentalUnits(asset.decimals).toIntegerValue()
                                }
                            },
                        )
                    }
                    amounts.filter { a -> a <= BigDecimal.ZERO }.let { withdrawals ->
                        this.withdrawals.addAll(
                            withdrawals.map {
                                xyz.funkybit.sequencer.proto.withdrawal {
                                    this.asset = asset.name
                                    this.account = account.value
                                    this.amount = (-it).toFundamentalUnits(asset.decimals).toIntegerValue()
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
            assertEquals(asset.name, withdrawal.asset)
            assertEquals(account.value, withdrawal.account)
            assertEquals(
                expectedAmount.setScale(asset.decimals),
                withdrawal.delta.toBigInteger().fromFundamentalUnits(asset.decimals),
            )
            assertEquals(
                expectedWithdrawalFees.size,
                depositsAndWithdrawalsResponse.withdrawalsCreatedList.size,
            )
            assertEquals(
                expectedWithdrawalFees.toSet(),
                depositsAndWithdrawalsResponse.withdrawalsCreatedList.map {
                    it.fee.toBigInteger()
                }.toSet(),
            )
        } else {
            assertEquals(0, depositsAndWithdrawalsResponse.balancesChangedCount)
        }
        return depositsAndWithdrawalsResponse
    }

    fun deposit(user: SequencerUserId, asset: Asset, amount: BigDecimal) =
        depositsAndWithdrawals(user, asset, listOf(amount))

    fun withdrawal(user: SequencerUserId, asset: Asset, amount: BigDecimal, expectedAmount: BigDecimal? = amount, expectedWithdrawalFee: BigInteger = BigInteger.ZERO) =
        depositsAndWithdrawals(user, asset, listOf(-amount), expectedAmount?.negate(), listOf(expectedWithdrawalFee))

    fun failedWithdrawals(user: SequencerUserId, asset: Asset, amounts: List<BigDecimal>, expectedAmount: BigDecimal? = amounts.sum()): SequencerResponse {
        val failedWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedWithdrawals.addAll(
                        amounts.map {
                            xyz.funkybit.sequencer.proto.failedWithdrawal {
                                this.asset = asset.name
                                this.account = user.value
                                this.amount = it.toFundamentalUnits(asset.decimals).toIntegerValue()
                            }
                        },
                    )
                }
            },
        )
        if (expectedAmount != null) {
            assertEquals(1, failedWithdrawalsResponse.balancesChangedCount)
            val withdrawal = failedWithdrawalsResponse.balancesChangedList.first()
            assertEquals(asset.name, withdrawal.asset)
            assertEquals(user.value, withdrawal.account)
            assertEquals(expectedAmount.setScale(asset.decimals), withdrawal.delta.toBigInteger().fromFundamentalUnits(asset.decimals))
        } else {
            assertEquals(0, failedWithdrawalsResponse.balancesChangedCount)
        }
        return failedWithdrawalsResponse
    }

    fun failedSettlement(buyUser: SequencerUserId, sellUser: SequencerUserId, market: Market, trade: TradeCreated): SequencerResponse {
        val failedSettlementsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedSettlements.add(
                        xyz.funkybit.sequencer.proto.failedSettlement {
                            this.buyAccount = buyUser.value
                            this.sellAccount = sellUser.value
                            this.marketId = market.id.value
                            this.trade = trade
                        },
                    )
                }
            },
        )
        return failedSettlementsResponse
    }
}
