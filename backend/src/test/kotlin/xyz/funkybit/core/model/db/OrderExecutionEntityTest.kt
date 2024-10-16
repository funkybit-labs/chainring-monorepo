package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.utils.generateHexString
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testfixtures.DbTestHelpers.createMarket
import xyz.funkybit.testfixtures.DbTestHelpers.createNativeSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createSymbol
import xyz.funkybit.testfixtures.DbTestHelpers.createWallet
import xyz.funkybit.testutils.TestWithDb
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
    fun `list latest order executions for user`() {
        val (makerWallet, takerWallet) = transaction { Pair(createWallet(), createWallet()) }

        val makerWallet2 = transaction {
            createWallet(
                address = BitcoinAddress.SegWit.generate(bitcoinConfig.params),
                user = makerWallet.user,
            )
        }

        createOrders(
            listOf(
                Order(OrderId("maker_order_1"), makerWallet, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_2"), makerWallet, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_3"), makerWallet, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_4"), makerWallet, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_5"), makerWallet, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("maker_order_6"), makerWallet2, btcEthMarket, OrderType.Limit, OrderSide.Sell),
                Order(OrderId("taker_order_1"), takerWallet, btcEthMarket, OrderType.Market, OrderSide.Buy),
                Order(OrderId("taker_order_2"), takerWallet, btcEthMarket, OrderType.Market, OrderSide.Buy),
                Order(OrderId("taker_order_3"), takerWallet, btcEthMarket, OrderType.Market, OrderSide.Buy),
            ),
        )

        transaction {
            assertEquals(
                emptyList<Pair<TradeId, ExecutionRole>>(),
                OrderExecutionEntity.listLatestForUser(
                    takerWallet.userGuid,
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
                OrderExecutionEntity.listLatestForUser(
                    takerWallet.userGuid,
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
                OrderExecutionEntity.listLatestForUser(
                    takerWallet.userGuid,
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
                OrderExecutionEntity.listLatestForUser(
                    makerWallet.userGuid,
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
                OrderExecutionEntity.listLatestForUser(
                    makerWallet.userGuid,
                    maxSequencerResponses = 2,
                ).map { Pair(it.tradeGuid.value, it.role) },
            )
        }
    }

    private data class Order(
        val id: OrderId,
        val wallet: WalletId,
        val user: UserId,
        val market: MarketId,
        val type: OrderType,
        val side: OrderSide,
    ) {
        constructor(id: OrderId, wallet: WalletEntity, market: MarketId, type: OrderType, side: OrderSide) :
            this(id, wallet.guid.value, wallet.userGuid.value, market, type, side)
    }

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
                    this.userGuid = EntityID(it.user, UserTable)
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
                        side = order1.side,
                        responseSequence = trade.responseSequence!!,
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
                        side = order2.side,
                        responseSequence = trade.responseSequence!!,
                    )
                }
            }
        }
    }
}
