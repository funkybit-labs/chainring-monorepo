package co.chainring.core.model.db

import co.chainring.core.model.Symbol
import co.chainring.core.utils.generateHexString
import co.chainring.testfixtures.DbTestHelpers.createChain
import co.chainring.testfixtures.DbTestHelpers.createMarket
import co.chainring.testfixtures.DbTestHelpers.createNativeSymbol
import co.chainring.testfixtures.DbTestHelpers.createSymbol
import co.chainring.testfixtures.DbTestHelpers.createWallet
import co.chainring.testutils.TestWithDb
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class OrderExecutionEntityTest : TestWithDb() {
    private val btcEthMarket = MarketId("BTC:123/ETH:123")

    @BeforeEach
    fun setup() {
        transaction {
            val chain = createChain(ChainId(123UL), "test-chain")
            val btc = createNativeSymbol("BTC", chain.id.value, decimals = 18U)
            val eth = createSymbol("ETH", chain.id.value, decimals = 18U)
            createMarket(btc, eth, tickSize = "0.05".toBigDecimal(), lastPrice = "17.525".toBigDecimal()).id.value
        }
    }

    @Test
    fun `list latest order executions for wallet`() {
        val (makerWalletId, takerWalletId) = transaction { Pair(createWallet().id.value, createWallet().id.value) }

        createOrders(
            listOf(
                Order(OrderId("maker_order_1"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_2"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_3"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_4"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_5"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_6"), makerWalletId, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("taker_order_1"), takerWalletId, btcEthMarket, OrderType.Market, OrderSide.Buy),
                Order(OrderId("taker_order_2"), takerWalletId, btcEthMarket, OrderType.Market, OrderSide.Buy),
                Order(OrderId("taker_order_3"), takerWalletId, btcEthMarket, OrderType.Market, OrderSide.Buy),
            ),
        )

        transaction {
            assertEquals(
                emptyList<Pair<TradeId, ExecutionRole>>(),
                OrderExecutionEntity.listLatestForWallet(
                    WalletEntity[takerWalletId],
                    maxSequencerResponses = 10,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )
        }

        createOrderExecutions(
            listOf(
                SeqResponse(
                    sequence = 1L,
                    trades = listOf(
                        Pair(OrderId("taker_order_1"), OrderId("maker_order_1")),
                        Pair(OrderId("taker_order_1"), OrderId("maker_order_2")),
                    ),
                ),
                SeqResponse(
                    sequence = 2L,
                    trades = listOf(
                        Pair(OrderId("taker_order_2"), OrderId("maker_order_3")),
                        Pair(OrderId("taker_order_2"), OrderId("maker_order_4")),
                        Pair(OrderId("taker_order_3"), OrderId("maker_order_5")),
                        Pair(OrderId("taker_order_3"), OrderId("maker_order_6")),
                    ),
                ),
            ),
        )

        transaction {
            assertEquals(
                listOf(
                    Pair(TradeId("trade_taker_order_2_maker_order_3"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_2_maker_order_4"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_3_maker_order_5"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_3_maker_order_6"), ExecutionRole.Taker),
                ),
                OrderExecutionEntity.listLatestForWallet(
                    WalletEntity[takerWalletId],
                    maxSequencerResponses = 1,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )

            assertEquals(
                listOf(
                    Pair(TradeId("trade_taker_order_2_maker_order_3"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_2_maker_order_4"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_3_maker_order_5"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_3_maker_order_6"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_1_maker_order_1"), ExecutionRole.Taker),
                    Pair(TradeId("trade_taker_order_1_maker_order_2"), ExecutionRole.Taker),
                ),
                OrderExecutionEntity.listLatestForWallet(
                    WalletEntity[takerWalletId],
                    maxSequencerResponses = 2,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )

            assertEquals(
                listOf(
                    Pair(TradeId("trade_taker_order_2_maker_order_3"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_2_maker_order_4"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_3_maker_order_5"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_3_maker_order_6"), ExecutionRole.Maker),
                ),
                OrderExecutionEntity.listLatestForWallet(
                    WalletEntity[makerWalletId],
                    maxSequencerResponses = 1,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )

            assertEquals(
                listOf(
                    Pair(TradeId("trade_taker_order_2_maker_order_3"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_2_maker_order_4"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_3_maker_order_5"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_3_maker_order_6"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_1_maker_order_1"), ExecutionRole.Maker),
                    Pair(TradeId("trade_taker_order_1_maker_order_2"), ExecutionRole.Maker),
                ),
                OrderExecutionEntity.listLatestForWallet(
                    WalletEntity[makerWalletId],
                    maxSequencerResponses = 2,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )
        }
    }

    private data class Order(
        val id: OrderId,
        val wallet: WalletId,
        val market: MarketId,
        val type: OrderType,
        val side: OrderSide,
    )

    private data class SeqResponse(
        val sequence: Long,
        val trades: List<Pair<OrderId, OrderId>>,
    )

    private fun createOrders(orders: List<Order>) {
        transaction {
            orders.forEach {
                OrderEntity.new(it.id) {
                    this.nonce = ""
                    this.createdAt = Clock.System.now()
                    this.createdBy = ""
                    this.marketGuid = EntityID(it.market, MarketTable)
                    this.walletGuid = EntityID(it.wallet, WalletTable)
                    this.status = OrderStatus.Filled
                    this.type = it.type
                    this.amount = BigInteger.ONE
                    this.side = it.side
                    this.originalAmount = this.amount
                    this.signature = ""
                    this.sequencerTimeNs = BigInteger.ONE
                }
            }
        }
    }

    private fun createOrderExecutions(sequencerResponses: List<SeqResponse>) {
        transaction {
            sequencerResponses.forEach { seqResp ->
                val now = Clock.System.now()
                seqResp.trades.forEach { (order1Id, order2Id) ->
                    val order1 = OrderEntity[order1Id]
                    val order2 = OrderEntity[order2Id]
                    assert(order1.marketGuid == order2.marketGuid)

                    val trade = TradeEntity.create(
                        now,
                        order1.market,
                        amount = BigInteger.ONE,
                        price = BigDecimal.TEN,
                        tradeHash = generateHexString(32),
                        seqResp.sequence,
                        id = TradeId("trade_${order1.id.value.value}_${order2.id.value.value}"),
                    ).also {
                        it.settlementStatus = SettlementStatus.Pending
                    }
                    OrderExecutionEntity.create(
                        now,
                        orderEntity = order1,
                        counterOrderEntity = order2,
                        tradeEntity = trade,
                        role = if (order1.type == OrderType.Market || order1.type == OrderType.BackToBackMarket) {
                            ExecutionRole.Taker
                        } else {
                            ExecutionRole.Maker
                        },
                        feeAmount = BigInteger.ONE,
                        feeSymbol = Symbol("ETH"),
                        marketEntity = order1.market,
                    )
                    OrderExecutionEntity.create(
                        now,
                        orderEntity = order2,
                        counterOrderEntity = order1,
                        tradeEntity = trade,
                        role = if (order2.type == OrderType.Market || order2.type == OrderType.BackToBackMarket) {
                            ExecutionRole.Taker
                        } else {
                            ExecutionRole.Maker
                        },
                        feeAmount = BigInteger.ONE,
                        feeSymbol = Symbol("ETH"),
                        marketEntity = order1.market,
                    )
                }
            }
        }
    }
}
