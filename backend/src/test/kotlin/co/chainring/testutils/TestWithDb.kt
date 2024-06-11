package co.chainring.testutils

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.db.migrations
import co.chainring.core.db.upgrade
import co.chainring.core.model.Address
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.db.BalanceLogTable
import co.chainring.core.model.db.BalanceTable
import co.chainring.core.model.db.BlockchainNonceTable
import co.chainring.core.model.db.BlockchainTransactionTable
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.ChainSettlementBatchTable
import co.chainring.core.model.db.ChainTable
import co.chainring.core.model.db.DeployedSmartContractTable
import co.chainring.core.model.db.DepositTable
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketTable
import co.chainring.core.model.db.OHLCTable
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionTable
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderTable
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.SettlementBatchTable
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolTable
import co.chainring.core.model.db.TradeTable
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WalletTable
import co.chainring.core.model.db.WithdrawalTable
import co.chainring.core.utils.toFundamentalUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.lang.System.getenv
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

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
            WalletTable.deleteAll()
            BlockchainTransactionTable.deleteAll()
            BlockchainNonceTable.deleteAll()
            OHLCTable.deleteAll()
            MarketTable.deleteAll()
            SymbolTable.deleteAll()
            DeployedSmartContractTable.deleteAll()
            ChainTable.deleteAll()
        }
    }

    protected fun createChain(
        id: ChainId,
        name: String,
        jsonRpcUrl: String = "",
        blockExplorerNetName: String = "",
        blockExplorerUrl: String = "",
    ): ChainEntity =
        ChainEntity.create(id, name, jsonRpcUrl, blockExplorerNetName, blockExplorerUrl)

    protected fun createNativeSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = null,
            decimals = decimals,
            "native coin",
        )

    protected fun createSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = Address.generate(),
            decimals = decimals,
            "$name coin",
        )

    protected fun createMarket(
        baseSymbol: SymbolEntity,
        quoteSymbol: SymbolEntity,
        tickSize: BigDecimal,
        lastPrice: BigDecimal,
    ): MarketEntity =
        MarketEntity.create(baseSymbol, quoteSymbol, tickSize, lastPrice)

    protected fun createWallet(address: Address = Address.generate()): WalletEntity =
        WalletEntity.getOrCreate(address)

    protected fun createOrder(
        market: MarketEntity,
        wallet: WalletEntity,
        side: OrderSide,
        type: OrderType,
        amount: BigDecimal,
        price: BigDecimal?,
        status: OrderStatus,
        sequencerId: SequencerOrderId,
        id: OrderId = OrderId.generate(),
        createdAt: Instant = Clock.System.now(),
    ): OrderEntity =
        OrderEntity.new(id) {
            this.createdAt = createdAt
            this.createdBy = "system"
            this.marketGuid = market.guid
            this.walletGuid = wallet.guid
            this.status = status
            this.side = side
            this.type = type
            this.amount = amount.toFundamentalUnits(market.baseSymbol.decimals)
            this.originalAmount = this.amount
            this.price = price
            this.nonce = UUID.randomUUID().toString().replace("-", "")
            this.signature = "signature"
            this.sequencerOrderId = sequencerId
            this.sequencerTimeNs = BigInteger.ZERO
        }
}
