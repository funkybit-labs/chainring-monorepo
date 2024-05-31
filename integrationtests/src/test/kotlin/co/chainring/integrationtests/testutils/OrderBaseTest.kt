package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.client.ws.subscribeToLimits
import co.chainring.core.client.ws.subscribeToOrderBook
import co.chainring.core.client.ws.subscribeToOrders
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.client.ws.subscribeToTrades
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.SettlementBatchEntity
import co.chainring.core.model.db.SettlementBatchStatus
import co.chainring.core.model.db.SettlementBatchTable
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.assertOrdersMessageReceived
import co.chainring.integrationtests.utils.assertPricesMessageReceived
import co.chainring.integrationtests.utils.assertTradesMessageReceived
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.provider.Arguments
import java.time.Duration

open class OrderBaseTest {

    companion object {
        lateinit var symbols: Map<String, SymbolInfo>
        lateinit var btc: SymbolInfo
        lateinit var btc2: SymbolInfo
        lateinit var eth: SymbolInfo
        lateinit var eth2: SymbolInfo
        lateinit var usdc: SymbolInfo
        lateinit var usdc2: SymbolInfo
        lateinit var dai: SymbolInfo
        lateinit var btcEthMarket: Market
        lateinit var btc2Eth2Market: Market
        lateinit var btcUsdcMarket: Market
        lateinit var btc2Usdc2Market: Market
        lateinit var usdcDaiMarket: Market
        lateinit var btcbtc2Market: Market
        lateinit var chainIdBySymbol: Map<String, ChainId>

        @JvmStatic
        @BeforeAll
        fun loadSymbols() {
            val config = TestApiClient.getConfiguration()
            usdcDaiMarket = config.markets.first { it.id.value == "USDC/DAI" }
            btcEthMarket = config.markets.first { it.id.value == "BTC/ETH" }
            btc2Eth2Market = config.markets.first { it.id.value == "BTC2/ETH2" }
            btcUsdcMarket = config.markets.first { it.id.value == "BTC/USDC" }
            btc2Usdc2Market = config.markets.first { it.id.value == "BTC2/USDC2" }
            btcbtc2Market = config.markets.first { it.id.value == "BTC/BTC2" }
            symbols = config.chains.flatMap { it.symbols }.associateBy { it.name }
            btc = symbols.getValue("BTC")
            btc2 = symbols.getValue("BTC2")
            eth = symbols.getValue("ETH")
            eth2 = symbols.getValue("ETH2")
            usdc = symbols.getValue("USDC")
            usdc2 = symbols.getValue("USDC2")
            dai = symbols.getValue("DAI")
            chainIdBySymbol = config.chains.map { chain -> chain.symbols.map { it.name to chain.id } }.flatten().toMap()
        }

        @JvmStatic
        fun chainIndices() = listOf(
            Arguments.of(0),
            Arguments.of(1),
        )
    }

    fun setupTrader(
        marketId: MarketId,
        airdrops: List<AssetAmount>,
        deposits: List<AssetAmount>,
        subscribeToOrderBook: Boolean = true,
        subscribeToOrderPrices: Boolean = true,
    ): Triple<TestApiClient, Wallet, WsClient> {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            if (subscribeToOrderBook) {
                subscribeToOrderBook(marketId)
                assertOrderBookMessageReceived(marketId)
            }

            if (subscribeToOrderPrices) {
                subscribeToPrices(marketId)
                assertPricesMessageReceived(marketId)
            }

            subscribeToOrders()
            assertOrdersMessageReceived()

            subscribeToTrades()
            assertTradesMessageReceived()

            subscribeToBalances()
            assertBalancesMessageReceived()

            subscribeToLimits(marketId)
            assertLimitsMessageReceived(marketId)
        }

        airdrops.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            if (it.symbol.contractAddress == null) {
                Faucet.fund(wallet.address, it.inFundamentalUnits, chainId)
            } else {
                wallet.mintERC20(it)
            }
        }

        deposits.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            wallet.deposit(it)
            wsClient.assertBalancesMessageReceived()
            wsClient.assertLimitsMessageReceived(marketId)
        }

        assertBalances(deposits.map { ExpectedBalance(it) }, apiClient.getBalances().balances)

        return Triple(apiClient, wallet, wsClient)
    }

    fun waitForSettlementToFinish(
        tradeIds: List<TradeId>,
        expectedStatus: SettlementStatus = SettlementStatus.Completed,
    ) {
        await
            .withAlias("Waiting for trade settlement to finish. TradeIds: ${tradeIds.joinToString { it.value }}")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(20000L))
            .until {
                Faucet.mine()
                transaction {
                    TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(expectedStatus))
                } == tradeIds.size.toLong()
            }
    }

    fun waitForSettlementBatchToFinish() {
        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(20000L))
            .until {
                transaction {
                    SettlementBatchEntity.count(SettlementBatchTable.status.neq(SettlementBatchStatus.Completed)) == 0L
                }
            }
    }

    fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }.sortedBy { it.sequenceId }
    }
}
