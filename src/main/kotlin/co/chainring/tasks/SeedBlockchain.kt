package co.chainring.tasks

import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.GasProvider
import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
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

data class SymbolContractAddress(
    val symbolId: SymbolId,
    val address: Address
)

fun seedBlockchain(fixtures: Fixtures, chainRpcUrl: String, privateKey: String): List<SymbolContractAddress> {
    val blockchainClient = BlockchainClient(BlockchainClientConfig(url = chainRpcUrl, privateKeyHex = privateKey))
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db

    val symbolEntities = transaction { SymbolEntity.all().toList() }

    val symbolContractAddresses = fixtures.symbols.mapNotNull { symbol ->
        if (symbol.isNative) {
            null
        } else {
            val contractAddress = symbolEntities.firstOrNull { it.id.value == symbol.id }?.contractAddress ?: run {
                val contractAddress = blockchainClient.deployMockERC20(
                    symbol.name,
                    symbol.decimals.toBigInteger()
                )
                println("Deployed MockERC20 contract for ${symbol.name} to address $contractAddress")
                contractAddress
            }

            SymbolContractAddress(symbol.id, contractAddress)
        }
    }

    fixtures.wallets.forEach { wallet ->
        wallet.balances.forEach { (symbolId, balance) ->
            val symbol = fixtures.symbols.first { it.id == symbolId }

            println("Setting ${symbol.name} balance for ${wallet.address.value} to $balance")

            if (symbol.isNative) {
                blockchainClient.setNativeBalance(wallet.address, balance)
            } else {
                val symbolContractAddress = symbolContractAddresses.first { it.symbolId == symbolId }.address
                val currentBalance = blockchainClient.getERC20Balance(symbolContractAddress, wallet.address)
                if (currentBalance < balance) {
                    blockchainClient.mintERC20(
                        symbolContractAddress,
                        wallet.address,
                        balance - currentBalance
                    )
                } else if (currentBalance > balance) {
                    blockchainClient.burnERC20(
                        symbolContractAddress,
                        wallet.address,
                        currentBalance - balance
                    )
                }
            }
        }
    }

    return symbolContractAddresses
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

