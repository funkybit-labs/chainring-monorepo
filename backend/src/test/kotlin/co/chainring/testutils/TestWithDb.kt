package co.chainring.testutils

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.db.migrations
import co.chainring.core.db.upgrade
import co.chainring.core.model.db.BalanceLogTable
import co.chainring.core.model.db.BalanceTable
import co.chainring.core.model.db.BlockchainNonceTable
import co.chainring.core.model.db.BlockchainTransactionTable
import co.chainring.core.model.db.ChainSettlementBatchTable
import co.chainring.core.model.db.ChainTable
import co.chainring.core.model.db.DeployedSmartContractTable
import co.chainring.core.model.db.DepositTable
import co.chainring.core.model.db.FaucetDripTable
import co.chainring.core.model.db.MarketTable
import co.chainring.core.model.db.OHLCTable
import co.chainring.core.model.db.OrderExecutionTable
import co.chainring.core.model.db.OrderTable
import co.chainring.core.model.db.SettlementBatchTable
import co.chainring.core.model.db.SymbolTable
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.WalletLinkedSignerTable
import co.chainring.core.model.db.WalletTable
import co.chainring.core.model.db.WithdrawalTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.lang.System.getenv

open class TestWithDb {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val isIntegrationRun = (getenv("INTEGRATION_RUN") ?: "0") == "1"

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            Assumptions.assumeFalse(isIntegrationRun)
            val db = Database.connect(DbConfig(port = 5433))
            db.upgrade(migrations, logger)
            TransactionManager.defaultDatabase = db
        }
    }

    @BeforeEach
    fun cleanupDb() {
        transaction {
            ChainSettlementBatchTable.deleteAll()
            SettlementBatchTable.deleteAll()
            OrderExecutionTable.deleteAll()
            TradeTable.deleteAll()
            OrderTable.deleteAll()
            BalanceLogTable.deleteAll()
            BalanceTable.deleteAll()
            DepositTable.deleteAll()
            WithdrawalTable.deleteAll()
            WalletLinkedSignerTable.deleteAll()
            WalletTable.deleteAll()
            BlockchainTransactionTable.deleteAll()
            BlockchainNonceTable.deleteAll()
            OHLCTable.deleteAll()
            MarketTable.deleteAll()
            FaucetDripTable.deleteAll()
            SymbolTable.deleteAll()
            DeployedSmartContractTable.deleteAll()
            ChainTable.deleteAll()
        }
    }
}
