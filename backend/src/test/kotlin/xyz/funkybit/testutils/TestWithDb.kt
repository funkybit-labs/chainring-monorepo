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
import xyz.funkybit.core.model.db.ArchStateUtxoLogTable
import xyz.funkybit.core.model.db.ArchStateUtxoTable
import xyz.funkybit.core.model.db.BalanceLogTable
import xyz.funkybit.core.model.db.BalanceTable
import xyz.funkybit.core.model.db.BitcoinWalletStateTable
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.BlockchainTransactionTable
import xyz.funkybit.core.model.db.BroadcasterJobTable
import xyz.funkybit.core.model.db.ChainSettlementBatchTable
import xyz.funkybit.core.model.db.ChainTable
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
import xyz.funkybit.core.model.db.TradeTable
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
            BlockchainTransactionTable.deleteAll()
            BalanceLogTable.deleteAll()
            BalanceTable.deleteAll()
            WalletLinkedSignerTable.deleteAll()
            LimitTable.deleteAll()
            WalletTable.deleteAll()
            UserTable.deleteAll()
            OHLCTable.deleteAll()
            FaucetDripTable.deleteAll()
            MarketTable.deleteAll()
            SymbolTable.deleteAll()
            ChainTable.deleteAll()
            BitcoinWalletStateTable.deleteAll()
            ArchStateUtxoLogTable.deleteAll()
            ArchStateUtxoTable.deleteAll()
        }
    }
}

fun isTestEnvRun(): Boolean =
    (getenv("TEST_ENV_RUN") ?: "0") == "1"
