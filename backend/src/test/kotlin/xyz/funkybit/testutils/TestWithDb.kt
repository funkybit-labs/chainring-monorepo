package xyz.funkybit.testutils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.connect
import xyz.funkybit.core.db.migrations
import xyz.funkybit.core.db.upgrade
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexTable
import xyz.funkybit.core.model.db.ArchAccountTable
import xyz.funkybit.core.model.db.BalanceLogTable
import xyz.funkybit.core.model.db.BalanceTable
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorTable
import xyz.funkybit.core.model.db.BitcoinUtxoTable
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.BlockchainTransactionTable
import xyz.funkybit.core.model.db.BroadcasterJobTable
import xyz.funkybit.core.model.db.ChainSettlementBatchTable
import xyz.funkybit.core.model.db.ChainTable
import xyz.funkybit.core.model.db.DeployedSmartContractTable
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.FaucetDripTable
import xyz.funkybit.core.model.db.LimitTable
import xyz.funkybit.core.model.db.MarketTable
import xyz.funkybit.core.model.db.OHLCTable
import xyz.funkybit.core.model.db.OrderBookSnapshotTable
import xyz.funkybit.core.model.db.OrderExecutionTable
import xyz.funkybit.core.model.db.OrderTable
import xyz.funkybit.core.model.db.SettlementBatchTable
import xyz.funkybit.core.model.db.SymbolTable
import xyz.funkybit.core.model.db.TestnetChallengePNLTable
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardTable
import xyz.funkybit.core.model.db.TradeTable
import xyz.funkybit.core.model.db.UserAccountLinkingIntentTable
import xyz.funkybit.core.model.db.UserLinkedAccountTable
import xyz.funkybit.core.model.db.UserTable
import xyz.funkybit.core.model.db.WalletLinkedSignerTable
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalTable
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserTable
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserWalletTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGameReactionTimeTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserTable
import java.lang.System.getenv

open class TestWithDb {
    companion object {
        private val logger = KotlinLogging.logger {}

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            Assumptions.assumeFalse(isTestEnvRun())
            val db = Database.connect(DbConfig(port = 5433))
            db.upgrade(migrations, logger)
            TransactionManager.defaultDatabase = db
        }
    }

    @BeforeEach
    fun cleanupDb() {
        transaction {
            BitcoinUtxoTable.deleteAll()
            BitcoinUtxoAddressMonitorTable.deleteAll()
            BlockTable.deleteAll()
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
            BalanceLogTable.deleteAll()
            BalanceTable.deleteAll()
            WalletLinkedSignerTable.deleteAll()
            LimitTable.deleteAll()
            TestnetChallengePNLTable.deleteAll()
            TestnetChallengeUserRewardTable.deleteAll()
            WalletTable.deleteAll()
            UserAccountLinkingIntentTable.deleteAll()
            UserLinkedAccountTable.deleteAll()
            UserTable.deleteAll()
            OHLCTable.deleteAll()
            FaucetDripTable.deleteAll()
            MarketTable.deleteAll()
            ArchAccountTable.deleteAll()
            SymbolTable.deleteAll()
            ChainTable.deleteAll()
            ArchAccountTable.deleteAll()
            DeployedSmartContractTable.deleteAll()
        }
    }
}

fun isTestEnvRun(): Boolean =
    (getenv("TEST_ENV_RUN") ?: "0") == "1"
