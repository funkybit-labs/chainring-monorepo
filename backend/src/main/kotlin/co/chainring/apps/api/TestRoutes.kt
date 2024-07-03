package co.chainring.apps.api

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.FeeRate
import co.chainring.core.model.MarketMinFee
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.WithdrawalFee
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.RuntimeException
import java.math.BigDecimal
import java.math.BigInteger

class TestRoutes(
    private val sequencerClient: SequencerClient,
) {
    private val logger = KotlinLogging.logger { }

    companion object {
        @Serializable
        data class CreateMarketInSequencer(
            val id: String,
            val tickSize: BigDecimalJson,
            val marketPrice: BigDecimalJson,
            val baseDecimals: Int,
            val quoteDecimals: Int,
            val minFee: BigDecimalJson,
        )

        @Serializable
        data class SetFeeRatesInSequencer(
            val maker: FeeRate,
            val taker: FeeRate,
        )

        @Serializable
        data class SetWithdrawalFeesInSequencer(
            val withdrawalFees: List<WithdrawalFee>,
        )

        @Serializable
        data class SetMarketMinFeesInSequencer(
            val marketMinFees: List<MarketMinFee>,
        )

        @Serializable
        data class StateDump(
            val makerFeeRate: FeeRate,
            val takerFeeRate: FeeRate,
            val balances: List<Balance>,
            val markets: List<Market>,
        ) {
            @Serializable
            data class Balance(
                val wallet: String,
                val asset: String,
                val amount: BigIntegerJson,
                val consumed: List<Consumption>,
            )

            @Serializable
            data class Consumption(
                val marketId: String,
                val consumed: BigIntegerJson,
            )

            @Serializable
            data class Market(
                val id: String,
                val tickSize: BigDecimalJson,
                val marketPrice: BigDecimalJson,
                val maxLevels: Int,
                val maxOrdersPerLevel: Int,
                val baseDecimals: Int,
                val quoteDecimals: Int,
                val maxOfferIx: Int,
                val minBidIx: Int,
                val levels: List<OrderBookLevel>,
                val minFee: BigIntegerJson,
            )

            @Serializable
            data class OrderBookLevel(
                val levelIx: Int,
                val side: String,
                val price: BigDecimalJson,
                val maxOrderCount: Int,
                val totalQuantity: BigIntegerJson,
                val orderHead: Int,
                val orderTail: Int,
                val orders: List<LevelOrder>,
            )

            @Serializable
            data class LevelOrder(
                val guid: Long,
                val wallet: String,
                val quantity: BigIntegerJson,
                val levelIx: Int,
                val originalQuantity: BigIntegerJson,
            )
        }
    }

    private val resetSequencer: ContractRoute =
        "sequencer" meta {
            operationId = "reset-sequencer"
            summary = "Reset Sequencer"
            tags += listOf(Tag("test"))
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { _ ->
            runBlocking {
                val sequencerResponse = sequencerClient.reset()
                if (sequencerResponse.hasError()) {
                    throw RuntimeException("Failed to reset sequencer, error: ${sequencerResponse.error}")
                }
            }
            Response(Status.NO_CONTENT)
        }

    private val getSequencerState: ContractRoute = run {
        val responseBody = Body.auto<StateDump>().toLens()

        "sequencer-state" meta {
            operationId = "get-sequencer-state"
            summary = "Get Sequencer state"
            tags += listOf(Tag("test"))
            returning(
                Status.OK,
                responseBody to StateDump(
                    takerFeeRate = FeeRate.fromPercents(1.0),
                    makerFeeRate = FeeRate.fromPercents(2.0),
                    balances = listOf(
                        StateDump.Balance(
                            wallet = "wallet",
                            asset = "asset",
                            amount = "123".toBigInteger(),
                            consumed = listOf(
                                StateDump.Consumption(
                                    marketId = "marketId",
                                    consumed = "123".toBigInteger(),
                                ),
                            ),
                        ),
                    ),
                    markets = listOf(
                        StateDump.Market(
                            id = "marketId",
                            tickSize = "123".toBigDecimal(),
                            marketPrice = "123".toBigDecimal(),
                            maxLevels = 123,
                            maxOrdersPerLevel = 123,
                            baseDecimals = 18,
                            quoteDecimals = 18,
                            maxOfferIx = 123,
                            minBidIx = 123,
                            minFee = "123".toBigInteger(),
                            levels = listOf(
                                StateDump.OrderBookLevel(
                                    levelIx = 123,
                                    side = "Buy",
                                    price = "123".toBigDecimal(),
                                    maxOrderCount = 123,
                                    totalQuantity = "123".toBigInteger(),
                                    orderHead = 123,
                                    orderTail = 123,
                                    orders = listOf(
                                        StateDump.LevelOrder(
                                            guid = 123L,
                                            wallet = "Wallet",
                                            quantity = "123".toBigInteger(),
                                            levelIx = 123,
                                            originalQuantity = "123".toBigInteger(),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        } bindContract Method.GET to { _ ->
            runBlocking {
                val sequencerResponse = sequencerClient.getState()
                if (sequencerResponse.hasError()) {
                    throw RuntimeException("Failed to get sequencer state, error: ${sequencerResponse.error}")
                }

                val walletAddresses = transaction {
                    val walletIds = sequencerResponse.stateDump.balancesList.map { SequencerWalletId(it.wallet) }.toSet()
                    WalletEntity
                        .getBySequencerIds(walletIds)
                        .associateBy(
                            keySelector = { it.sequencerId.value },
                            valueTransform = { it.address.value },
                        )
                }

                Response(Status.OK).with(
                    responseBody of StateDump(
                        makerFeeRate = FeeRate(sequencerResponse.stateDump.feeRates.maker),
                        takerFeeRate = FeeRate(sequencerResponse.stateDump.feeRates.taker),
                        balances = sequencerResponse.stateDump.balancesList.map { b ->
                            StateDump.Balance(
                                wallet = walletAddresses[b.wallet] ?: b.wallet.toString(),
                                asset = b.asset,
                                amount = b.amount.toBigInteger(),
                                consumed = b.consumedList.map { c ->
                                    StateDump.Consumption(
                                        marketId = c.marketId,
                                        consumed = c.consumed.toBigInteger(),
                                    )
                                },
                            )
                        },
                        markets = sequencerResponse.stateDump.marketsList.map { m ->
                            StateDump.Market(
                                id = m.id,
                                tickSize = m.tickSize.toBigDecimal(),
                                marketPrice = m.marketPrice.toBigDecimal(),
                                maxLevels = m.maxLevels,
                                maxOrdersPerLevel = m.maxOrdersPerLevel,
                                baseDecimals = m.baseDecimals,
                                quoteDecimals = m.quoteDecimals,
                                maxOfferIx = m.maxOfferIx,
                                minBidIx = m.minBidIx,
                                minFee = m.minFee?.toBigInteger() ?: BigInteger.ZERO,
                                levels = m.levelsList.map { l ->
                                    StateDump.OrderBookLevel(
                                        levelIx = l.levelIx,
                                        side = l.side.name,
                                        price = l.price.toBigDecimal(),
                                        maxOrderCount = l.maxOrderCount,
                                        totalQuantity = l.totalQuantity.toBigInteger(),
                                        orderHead = l.orderHead,
                                        orderTail = l.orderTail,
                                        orders = l.ordersList.map { lo ->
                                            StateDump.LevelOrder(
                                                guid = lo.guid,
                                                wallet = walletAddresses[lo.wallet] ?: lo.wallet.toString(),
                                                quantity = lo.quantity.toBigInteger(),
                                                levelIx = lo.levelIx,
                                                originalQuantity = lo.originalQuantity.toBigInteger(),
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    ),
                )
            }
        }
    }

    private val createMarketInSequencer: ContractRoute = run {
        val requestBody = Body.auto<CreateMarketInSequencer>().toLens()

        "sequencer-markets" meta {
            operationId = "sequencer-markets"
            summary = "Create Market in Sequencer"
            tags += listOf(Tag("test"))
            receiving(
                requestBody to CreateMarketInSequencer(
                    id = "BTC/ETH",
                    tickSize = "0.05".toBigDecimal(),
                    marketPrice = "17.55".toBigDecimal(),
                    baseDecimals = 18,
                    quoteDecimals = 18,
                    minFee = "0.000005".toBigDecimal(),
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.POST to { request ->
            val payload = requestBody(request)
            runBlocking {
                val sequencerResponse = sequencerClient.createMarket(
                    marketId = payload.id,
                    tickSize = payload.tickSize,
                    marketPrice = payload.marketPrice,
                    baseDecimals = payload.baseDecimals,
                    quoteDecimals = payload.quoteDecimals,
                    minFee = payload.minFee,
                )
                if (sequencerResponse.hasError()) {
                    throw RuntimeException("Failed to create market in sequencer, error: ${sequencerResponse.error}")
                }
            }
            Response(Status.CREATED)
        }
    }

    private val setFeeRatesInSequencer: ContractRoute = run {
        val requestBody = Body.auto<SetFeeRatesInSequencer>().toLens()

        "sequencer-fee-rates" meta {
            operationId = "sequencer-fee-rates"
            summary = "Set fee rates in Sequencer"
            tags += listOf(Tag("test"))
            receiving(
                requestBody to SetFeeRatesInSequencer(
                    maker = FeeRate.fromPercents(1.0),
                    taker = FeeRate.fromPercents(2.0),
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.PUT to { request ->
            val payload = requestBody(request)
            runBlocking {
                val sequencerResponse = sequencerClient.setFeeRates(
                    FeeRates(maker = payload.maker, taker = payload.taker),
                )
                if (sequencerResponse.hasError()) {
                    throw RuntimeException("Failed to set fee rates in sequencer, error: ${sequencerResponse.error}")
                }
            }
            Response(Status.OK)
        }
    }

    private val setWithdrawalFeesInSequencer: ContractRoute = run {
        val requestBody = Body.auto<SetWithdrawalFeesInSequencer>().toLens()

        "sequencer-withdrawal-fees" meta {
            operationId = "sequencer-withdrawal-fees"
            summary = "Set withdrawal fees in Sequencer"
            tags += listOf(Tag("test"))
            receiving(
                requestBody to SetWithdrawalFeesInSequencer(
                    withdrawalFees = listOf(
                        WithdrawalFee(Symbol("BTC"), BigInteger.ONE),
                    ),
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.PUT to { request ->
            val payload = requestBody(request)
            runBlocking {
                val sequencerResponse = sequencerClient.setWithdrawalFees(
                    payload.withdrawalFees,
                )
                if (sequencerResponse.hasError()) {
                    throw RuntimeException("Failed to set withdrawal fees in sequencer, error: ${sequencerResponse.error}")
                }
            }
            Response(Status.OK)
        }
    }

    private val setMarketMinFeesInSequencer: ContractRoute = run {
        val requestBody = Body.auto<SetMarketMinFeesInSequencer>().toLens()

        "sequencer-market-min-fees" meta {
            operationId = "sequencer-market-min-fees"
            summary = "Set market min fees in Sequencer"
            tags += listOf(Tag("test"))
            receiving(
                requestBody to SetMarketMinFeesInSequencer(
                    marketMinFees = listOf(
                        MarketMinFee(MarketId("BTC:1337/ETH:1337"), BigDecimal.ONE),
                    ),
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.PUT to { request ->
            val payload = requestBody(request)
            transaction {
                runBlocking {
                    val sequencerResponse = sequencerClient.setMarketMinFees(
                        payload.marketMinFees.associate {
                            it.marketId to it.minFee.toFundamentalUnits(MarketEntity[it.marketId].quoteSymbol.decimals.toInt())
                        },
                    )
                    if (sequencerResponse.hasError()) {
                        throw RuntimeException("Failed to set market min fees in sequencer, error: ${sequencerResponse.error}")
                    }
                }
            }
            Response(Status.OK)
        }
    }

    val routes = listOf(
        createMarketInSequencer,
        setFeeRatesInSequencer,
        resetSequencer,
        getSequencerState,
        setWithdrawalFeesInSequencer,
        setMarketMinFeesInSequencer,
    )
}
