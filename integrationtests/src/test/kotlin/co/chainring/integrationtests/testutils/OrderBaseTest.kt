package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.SymbolInfo
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
import co.chainring.integrationtests.utils.blocking
import co.chainring.integrationtests.utils.subscribeToBalances
import co.chainring.integrationtests.utils.subscribeToLimits
import co.chainring.integrationtests.utils.subscribeToOrderBook
import co.chainring.integrationtests.utils.subscribeToOrders
import co.chainring.integrationtests.utils.subscribeToPrices
import co.chainring.integrationtests.utils.subscribeToTrades
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
            val(chain1, chain2) = config.chains.map { it.id }
            usdcDaiMarket = config.markets.first { it.id.value == "USDC:$chain1/DAI:$chain1" }
            btcEthMarket = config.markets.first { it.id.value == "BTC:$chain1/ETH:$chain1" }
            btc2Eth2Market = config.markets.first { it.id.value == "BTC:$chain2/ETH:$chain2" }
            btcUsdcMarket = config.markets.first { it.id.value == "BTC:$chain1/USDC:$chain1" }
            btc2Usdc2Market = config.markets.first { it.id.value == "BTC:$chain2/USDC:$chain2" }
            btcbtc2Market = config.markets.first { it.id.value == "BTC:$chain1/BTC:$chain2" }
            symbols = config.chains.flatMap { it.symbols }.associateBy { it.name }
            btc = symbols.getValue("BTC:$chain1")
            btc2 = symbols.getValue("BTC:$chain2")
            eth = symbols.getValue("ETH:$chain1")
            eth2 = symbols.getValue("ETH:$chain2")
            usdc = symbols.getValue("USDC:$chain1")
            usdc2 = symbols.getValue("USDC:$chain2")
            dai = symbols.getValue("DAI:$chain1")
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
