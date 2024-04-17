package co.chainring.apps.api

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.services.ExchangeService
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

class TestRoutes(
    private val exchangeService: ExchangeService,
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
        )

        @Serializable
        data class CreateSequencerDeposit(
            val symbol: String,
            val amount: BigIntegerJson,
        )

        @Serializable
        data class StateDump(
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
            ApiUtils.runCatchingValidation {
                runBlocking {
                    val sequencerResponse = sequencerClient.reset()
                    if (sequencerResponse.hasError()) {
                        throw RuntimeException("Failed to reset sequencer, error: ${sequencerResponse.error}")
                    }
                }
                Response(Status.NO_CONTENT)
            }
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
            ApiUtils.runCatchingValidation {
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
                ),
            )
            returning(
                Status.CREATED,
            )
        } bindContract Method.POST to { request ->
            ApiUtils.runCatchingValidation {
                val payload = requestBody(request)
                runBlocking {
                    val sequencerResponse = sequencerClient.createMarket(
                        marketId = payload.id,
                        tickSize = payload.tickSize,
                        marketPrice = payload.marketPrice,
                        baseDecimals = payload.baseDecimals,
                        quoteDecimals = payload.quoteDecimals,
                    )
                    if (sequencerResponse.hasError()) {
                        throw RuntimeException("Failed to create market in sequencer, error: ${sequencerResponse.error}")
                    }
                }
                Response(Status.CREATED)
            }
        }
    }

    val routes = listOf(
        createMarketInSequencer,
        resetSequencer,
        getSequencerState,
    )
}
