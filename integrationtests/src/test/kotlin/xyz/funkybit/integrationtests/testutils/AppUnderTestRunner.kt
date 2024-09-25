package xyz.funkybit.integrationtests.testutils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import xyz.funkybit.apps.api.ApiApp
import xyz.funkybit.apps.api.ApiAppConfig
import xyz.funkybit.apps.api.TestRoutes
import xyz.funkybit.apps.ring.RingApp
import xyz.funkybit.apps.ring.RingAppConfig
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.notifyDbListener
import xyz.funkybit.core.model.MarketMinFee
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.WithdrawalFee
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexTable
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountTable
import xyz.funkybit.core.model.db.BalanceLogTable
import xyz.funkybit.core.model.db.BalanceTable
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorTable
import xyz.funkybit.core.model.db.BitcoinUtxoTable
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.BlockchainTransactionTable
import xyz.funkybit.core.model.db.BroadcasterJobTable
import xyz.funkybit.core.model.db.ChainSettlementBatchTable
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.FaucetDripTable
import xyz.funkybit.core.model.db.KeyValueStore
import xyz.funkybit.core.model.db.LimitTable
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCTable
import xyz.funkybit.core.model.db.OrderBookSnapshotTable
import xyz.funkybit.core.model.db.OrderExecutionTable
import xyz.funkybit.core.model.db.OrderTable
import xyz.funkybit.core.model.db.SettlementBatchTable
import xyz.funkybit.core.model.db.TestnetChallengePNLTable
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardTable
import xyz.funkybit.core.model.db.TradeTable
import xyz.funkybit.core.model.db.UserTable
import xyz.funkybit.core.model.db.WalletLinkedSignerTable
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.WithdrawalTable
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserTable
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserWalletTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGameReactionTimeTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserTable
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.TestBlockchainClient
import xyz.funkybit.sequencer.apps.GatewayApp
import xyz.funkybit.sequencer.apps.GatewayConfig
import xyz.funkybit.sequencer.apps.SequencerApp
import xyz.funkybit.sequencer.apps.SequencerResponseProcessorApp
import xyz.funkybit.tasks.fixtures.Fixtures
import xyz.funkybit.tasks.fixtures.getFixtures
import xyz.funkybit.tasks.migrateDatabase
import xyz.funkybit.tasks.seedBlockchain
import xyz.funkybit.tasks.seedDatabase
import java.lang.System.getenv
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// This extension allows us to start the app under test only once
class AppUnderTestRunner : BeforeAllCallback, BeforeEachCallback {
    @OptIn(ExperimentalUnsignedTypes::class)
    override fun beforeAll(context: ExtensionContext) {
        val blockchainClients = ChainManager.blockchainConfigs.map {
            TestBlockchainClient(it)
        }
        val fixtures = getFixtures(blockchainClients)

        context
            .root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .put("fixtures", fixtures)

        context
            .root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .getOrComputeIfAbsent("app under test") {
                object : CloseableResource {
                    val logger = KotlinLogging.logger {}
                    private val dbConfig = DbConfig()
                    private val apiApp = ApiApp(ApiAppConfig(httpPort = 9000, dbConfig = dbConfig))
                    private val ringApp = RingApp(RingAppConfig(dbConfig = dbConfig, repeaterAutomaticTaskScheduling = false))
                    private val gatewayApp = GatewayApp(GatewayConfig(port = 5337))
                    private val sequencerApp = SequencerApp(
                        // we want sequencer to start from the clean-slate
                        checkpointsQueue = null,
                        inSandboxMode = true,
                    )
                    private val sequencerResponseProcessorApp = SequencerResponseProcessorApp(
                        sequencerApp.inputQueue,
                        sequencerApp.outputQueue,
                        onAbnormalStop = {},
                    )

                    init {
                        migrateDatabase()

                        // activate auto mining to speed up blockchain seeding
                        blockchainClients.forEach {
                            it.setAutoMining(true)
                        }

                        if (!isTestEnvRun()) {
                            deleteAll()
                            sequencerApp.start()
                            sequencerResponseProcessorApp.start()
                            gatewayApp.start()
                            transaction {
                                BitcoinUtxoTable.deleteAll()
                                BitcoinUtxoAddressMonitorTable.deleteAll()
                                blockchainClients.forEach { blockchainClient ->
                                    DeployedSmartContractEntity.findLastDeployedContractByNameAndChain(
                                        ContractType.Exchange.name,
                                        blockchainClient.chainId,
                                    )?.deprecated = true
                                }
                                val programAccount = ArchAccountEntity.findProgramAccount()
                                val onChainAccount = try {
                                    programAccount?.let { ArchNetworkClient.readAccountInfo(it.rpcPubkey()) }
                                } catch (e: Exception) {
                                    null
                                }
                                ArchAccountBalanceIndexTable.deleteAll()
                                if (
                                    programAccount?.status != ArchAccountStatus.Complete ||
                                    ArchAccountEntity.findProgramStateAccount()?.status != ArchAccountStatus.Complete ||
                                    onChainAccount == null ||
                                    !onChainAccount.data.toByteArray().contentEquals(javaClass.getResource("/exchangeprogram.so")!!.readBytes())
                                ) {
                                    DeployedSmartContractEntity.deleteDeployedContractByNameAndChain(
                                        ContractType.Exchange.name,
                                        BitcoinClient.chainId,
                                    )
                                    ArchAccountTable.deleteAll()
                                } else {
                                    if (DeployedSmartContractEntity.validContracts(BitcoinClient.chainId).isNotEmpty()) {
                                        BitcoinUtxoAddressMonitorEntity.createIfNotExists(DeployedSmartContractEntity.programBitcoinAddress())
                                    }
                                }
                                WithdrawalEntity.findPending().forEach { it.update(WithdrawalStatus.Failed, "restarting test") }
                                BlockTable.deleteAll()
                            }
                            ringApp.start()
                            apiApp.start()
                        }
                        // wait for contracts to load
                        blockchainClients.forEach { blockchainClient ->
                            await
                                .pollInSameThread()
                                .pollDelay(Duration.ofMillis(100))
                                .pollInterval(Duration.ofMillis(100))
                                .atMost(Duration.ofMillis(30000L))
                                .until {
                                    transaction {
                                        DeployedSmartContractEntity.validContracts(blockchainClient.chainId)
                                            .map { it.name } == listOf(ContractType.Exchange.name)
                                    }
                                }
                        }

                        val symbolContractAddresses = seedBlockchain(fixtures)
                        seedDatabase(fixtures, symbolContractAddresses)

                        // during tests blocks will be mined manually
                        blockchainClients.forEach {
                            it.setAutoMining(false)
                            it.setIntervalMining(60.minutes)
                        }
                    }

                    @Throws(Throwable::class)
                    override fun close() {
                        if (!isTestEnvRun()) {
                            apiApp.stop()
                            ringApp.stop()
                            sequencerApp.stop()
                            gatewayApp.stop()
                        }

                        // revert back to interval mining for `test` env to work normally
                        blockchainClients.forEach {
                            it.setIntervalMining(1.seconds)
                        }
                    }
                }
            }
    }

    override fun beforeEach(context: ExtensionContext) {
        waitForActivityToComplete()

        TestApiClient.resetSequencer()
        transaction {
            KeyValueStore.deleteAll()
        }

        val fixtures: Fixtures = context
            .root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .get("fixtures", Fixtures::class.java)

        TestApiClient.setFeeRatesInSequencer(fixtures.feeRates)

        TestApiClient.setWithdrawalFeesInSequencer(
            fixtures.symbols.map {
                WithdrawalFee(Symbol(it.name), it.withdrawalFee.toFundamentalUnits(it.decimals))
            },
        )

        fixtures.markets.forEach { market ->
            val baseSymbol = fixtures.symbols.first { it.id == market.baseSymbol }
            val quoteSymbol = fixtures.symbols.first { it.id == market.quoteSymbol }

            TestApiClient.createMarketInSequencer(
                TestRoutes.Companion.CreateMarketInSequencer(
                    id = "${baseSymbol.name}/${quoteSymbol.name}",
                    tickSize = market.tickSize,
                    quoteDecimals = quoteSymbol.decimals,
                    baseDecimals = baseSymbol.decimals,
                    minFee = market.minFee,
                ),
            )
        }

        TestApiClient.setMarketMinFeesInSequencer(
            fixtures.markets.map { market ->
                val baseSymbol = fixtures.symbols.first { it.id == market.baseSymbol }
                val quoteSymbol = fixtures.symbols.first { it.id == market.quoteSymbol }
                MarketMinFee(
                    MarketId("${baseSymbol.name}/${quoteSymbol.name}"),
                    market.minFee,
                )
            },
        )

        try {
            deleteAll()
        } catch (e: Exception) {
            Thread.sleep(1000)
            deleteAll()
        }
    }

    private fun deleteAll() {
        transaction {
            TelegramBotUserWalletTable.deleteAll()
            TelegramBotUserTable.deleteAll()
            TelegramMiniAppUserRewardTable.deleteAll()
            TelegramMiniAppGameReactionTimeTable.deleteAll()
            TelegramMiniAppUserTable.deleteAll()
            BroadcasterJobTable.deleteAll()
            OrderExecutionTable.deleteAll()
            TradeTable.deleteAll()
            OrderTable.deleteAll()
            OrderBookSnapshotTable.deleteAll()
            ChainSettlementBatchTable.deleteAll()
            SettlementBatchTable.deleteAll()
            DepositTable.deleteAll()
            WithdrawalTable.deleteAll()
            ArchAccountBalanceIndexTable.deleteAll()
            BlockchainTransactionTable.deleteAll()
            TestnetChallengePNLTable.deleteAll()
            TestnetChallengeUserRewardTable.deleteAll()
            BalanceLogTable.deleteAll()
            BalanceTable.deleteAll()
            WalletLinkedSignerTable.deleteAll()
            LimitTable.deleteAll()
            WalletTable.deleteAll()
            UserTable.deleteAll()
            OHLCTable.deleteAll()
            FaucetDripTable.deleteAll()
            notifyDbListener("broadcaster_ctl", "clear-cache")
        }
    }
}

fun isTestEnvRun(): Boolean =
    (getenv("TEST_ENV_RUN") ?: "0") == "1"

fun isBitcoinDisabled(): Boolean =
    (getenv("BITCOIN_NETWORK_ENABLED") ?: "true") == "false"
