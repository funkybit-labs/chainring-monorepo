package co.chainring.tasks

import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.GasProvider
import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
import co.chainring.tasks.fixtures.Fixtures
import okhttp3.OkHttpClient
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3jService
import org.web3j.protocol.core.JsonRpc2_0Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Numeric
import java.math.BigInteger

fun seedDatabase(fixtures: Fixtures, chainRpcUrl: String, privateKey: String) {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db
    val blockchainClient = BlockchainClient(BlockchainClientConfig(url = chainRpcUrl, privateKeyHex = privateKey))

    transaction {
        fixtures.chains.forEach { chain ->
            if (ChainEntity.findById(chain.id) == null) {
                ChainEntity.create(chain.id, chain.name).flush()
                println("Created chain ${chain.name} with id=${chain.id}")
            }
        }

         val symbolEntities = fixtures.symbols.map{ symbol ->
             SymbolEntity.findById(SymbolId(symbol.chainId, symbol.name))
                 ?: run {
                     val contractAddress = if (!symbol.isNative && symbol.contractAddress == null) {
                         val contractAddress = blockchainClient.deployMockERC20(symbol.name, symbol.decimals.toBigInteger())
                         println("Deployed MockERC20 contract for ${symbol.name} to address $contractAddress")
                         contractAddress
                     } else {
                         symbol.contractAddress
                     }

                     SymbolEntity.create(
                         symbol.name,
                         symbol.chainId,
                         contractAddress,
                         decimals = symbol.decimals.toUByte(),
                         description = ""
                     ).also {
                         it.flush()
                         println("Created symbol ${symbol.name} with guid=${it.guid.value}")
                     }
                 }
         }.associateBy { Fixtures.SymbolId(it.name, it.chainId.value) }

        fixtures.markets.forEach { (baseSymbolId, quoteSymbolId) ->
            val baseSymbol = symbolEntities.getValue(baseSymbolId)
            val quoteSymbol = symbolEntities.getValue(quoteSymbolId)

            if (MarketEntity.findById(MarketId(baseSymbol, quoteSymbol)) == null) {
                val marketEntity = MarketEntity
                    .create(baseSymbol, quoteSymbol)
                    .also {
                        it.flush()
                    }

                println("Created market ${marketEntity.guid.value}")
            }
        }

        fixtures.wallets.forEach { wallet ->
            wallet.balances.forEach { (symbolId, balance) ->
                val symbol = symbolEntities.getValue(symbolId)

                println("Setting ${symbol.name} balance for ${wallet.address.value} to $balance")

                val tokenContractAddress = symbol.contractAddress
                if (tokenContractAddress == null) {
                    blockchainClient.setNativeBalance(wallet.address, balance)
                } else {
                    val currentBalance = blockchainClient.getERC20Balance(tokenContractAddress, wallet.address)
                    if (currentBalance < balance) {
                        blockchainClient.mintERC20(
                            tokenContractAddress,
                            wallet.address,
                            balance - currentBalance
                        )
                    } else if (currentBalance > balance) {
                        blockchainClient.burnERC20(
                            tokenContractAddress,
                            wallet.address,
                            currentBalance - balance
                        )
                    }
                }
            }
        }
    }
}

private class BlockchainClient(config: BlockchainClientConfig = BlockchainClientConfig()) {
    class AnvilRpc(web3jService: Web3jService) : JsonRpc2_0Web3j(web3jService) {
        fun setBalance(address: Address, amount: BigInteger): Request<String, VoidResponse?> =
            Request(
                "anvil_setBalance",
                listOf<String>(address.value, Numeric.encodeQuantity(amount)),
                web3jService,
                VoidResponse::class.java
            )
    }

    private val web3j = AnvilRpc(HttpService(config.url, OkHttpClient.Builder().build()))
    private val credentials = Credentials.create(config.privateKeyHex)
    private val chainId = ChainId(web3j.ethChainId().send().chainId)
    private val transactionManager = RawTransactionManager(
        web3j,
        credentials,
        chainId.value.toLong(),
        PollingTransactionReceiptProcessor(
            web3j,
            config.deploymentPollingIntervalInMs,
            config.maxPollingAttempts.toInt(),
        ),
    )

    private val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )

    fun deployMockERC20(tokenName: String, decimals: BigInteger): Address {
        val contract = MockERC20.deploy(
            web3j,
            transactionManager,
            gasProvider,
            "$tokenName Coin",
            tokenName,
            decimals
        ).send()
        return Address(contract.contractAddress)
    }

    fun getERC20Balance(tokenContractAddress: Address, holder: Address): BigInteger {
        return MockERC20.load(
            tokenContractAddress.value,
            web3j,
            transactionManager,
            gasProvider
        ).balanceOf(holder.value).send()
    }

    fun mintERC20(
        tokenContractAddress: Address,
        receiver: Address,
        amount: BigInteger
    ) {
        MockERC20.load(
            tokenContractAddress.value,
            web3j,
            transactionManager,
            gasProvider
        ).mint(receiver.value, amount).send()
    }

    fun burnERC20(
        tokenContractAddress: Address,
        receiver: Address,
        amount: BigInteger
    ) {
        MockERC20.load(
            tokenContractAddress.value,
            web3j,
            transactionManager,
            gasProvider
        ).burn(receiver.value, amount).send()
    }

    fun setNativeBalance(
        address: Address,
        amount: BigInteger
    ) {
        web3j.setBalance(address, amount).send()
    }
}

