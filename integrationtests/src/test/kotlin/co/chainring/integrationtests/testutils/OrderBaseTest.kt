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
import co.chainring.core.model.db.BlockchainTransactionStatus
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainSettlementBatchEntity
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
import co.chainring.core.model.db.TxHash
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
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.provider.Arguments
import org.web3j.crypto.Credentials

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
            deposit(wallet, apiClient, it)
            wsClient.assertBalancesMessageReceived()
            wsClient.assertLimitsMessageReceived(marketId)
        }

        assertBalances(deposits.map { ExpectedBalance(it) }, apiClient.getBalances().balances)

        return Triple(apiClient, wallet, wsClient)
    }

    fun waitForSettlementToFinishWithForking(tradeIds: List<TradeId>, rollbackSettlement: Boolean = false) {
        waitFor {
            transaction {
                TradeEntity[tradeIds.first()].settlementBatchGuid != null
            }
        }

        val chainBatchGuid = transaction { TradeEntity[tradeIds.first()].settlementBatch!!.chainBatches.first().guid }

        // wait for the prepareTx to be submitted
        waitFor {
            transaction {
                ChainSettlementBatchEntity[chainBatchGuid].prepararationTx.status == BlockchainTransactionStatus.Submitted
            }
        }

        // rolling back the onchain prepared batch and changing the tx hash, so it's not found simulates a fork since
        // the preparation tx will need to be resubmitted again
        // Not rolling back on chain but changing the tx hash simulates anvil restarting
        if (rollbackSettlement) {
            val submitterWallet = Wallet(TestApiClient(ecKeyPair = Credentials.create("0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba").ecKeyPair))
            submitterWallet.switchChain(transaction { ChainSettlementBatchEntity[chainBatchGuid].chainId.value })
            submitterWallet.rollbackSettlement()
        }

        // now change the hash in DB - this will cause it to think the tx does not exist
        transaction {
            ChainSettlementBatchEntity[chainBatchGuid].prepararationTx.txHash = TxHash("0x6d37aaf942f1679e7c34d241859017d5caf42f57f7c1b4f1f0c149c2649bb822")
        }

        waitFor {
            Faucet.mine()
            transaction {
                ChainSettlementBatchEntity[chainBatchGuid].submissionTx?.let {
                    listOf(BlockchainTransactionStatus.Submitted, BlockchainTransactionStatus.Confirmed, BlockchainTransactionStatus.Completed).contains(it.status)
                } ?: false
            }
        }

        // now change the submission hash - simulates anvil restarting
        transaction {
            ChainSettlementBatchEntity[chainBatchGuid].submissionTx!!.txHash = TxHash("0x6d37aaf942f1679e7c34d241859017d5caf42f57f7c1b4f1f0c149c2649bb833")
        }

        waitForSettlementToFinish(tradeIds)
    }

    fun waitForSettlementToFinish(tradeIds: List<TradeId>, expectedStatus: SettlementStatus = SettlementStatus.Completed) {
        waitFor {
            Faucet.mine()
            transaction {
                TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(expectedStatus))
            } == tradeIds.size.toLong()
        }
    }

    fun waitForSettlementBatchToFinish() {
        waitFor {
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
