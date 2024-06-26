package co.chainring.tasks

import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
import co.chainring.tasks.fixtures.Fixtures
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import java.math.BigInteger
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.utils.Numeric
import java.time.Duration

data class SymbolContractAddress(
    val symbolId: SymbolId,
    val address: Address
)

val blockchainClients = ChainManager.blockchainConfigs.map {
    FixturesBlockchainClient(it.copy(enableWeb3jLogging = false))
}
val blockchainClientsByChainId = blockchainClients.associateBy { it.chainId }

fun blockchainClient(chainId: ChainId) = blockchainClientsByChainId.getValue(chainId)

fun seedBlockchain(fixtures: Fixtures): List<SymbolContractAddress> {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db

    val symbolEntities = transaction { SymbolEntity.all().toList() }

    val symbolContractAddresses = fixtures.symbols.mapNotNull { symbol ->
        if (symbol.isNative) {
            null
        } else {
            val contractAddress = symbolEntities.firstOrNull { it.id.value == symbol.id }?.contractAddress ?: run {
                val contractAddress = blockchainClient(symbol.chainId).deployMockERC20(
                    symbol.name.replace(Regex(":.*"), ""),
                    symbol.decimals.toBigInteger()
                )
                println("Deployed MockERC20 contract for ${symbol.name} to address $contractAddress")
                contractAddress
            }

            SymbolContractAddress(symbol.id, contractAddress)
        }
    }

    val txHashes = fixtures.wallets.flatMap { wallet ->
        wallet.balances.mapNotNull { (symbolId, balance) ->
            val symbol = fixtures.symbols.first { it.id == symbolId }
            val blockchainClient = blockchainClient(symbol.chainId)

            println("Setting ${symbol.name} balance for ${wallet.address.value} to $balance")

            if (symbol.isNative) {
                blockchainClient.setNativeBalance(wallet.address, balance)
                null
            } else {
                val symbolContractAddress = symbolContractAddresses.first { it.symbolId == symbolId }.address
                val currentBalance = blockchainClient.getERC20Balance(symbolContractAddress, wallet.address)
                if (currentBalance < balance) {
                    Pair(
                        symbol.chainId,
                        blockchainClient.asyncMintERC20(
                            symbolContractAddress,
                            wallet.address,
                            balance - currentBalance
                        )
                    )
                } else if (currentBalance > balance) {
                    Pair(
                        symbol.chainId,
                        blockchainClient.asyncBurnERC20(
                            symbolContractAddress,
                            wallet.address,
                            currentBalance - balance
                        )
                    )
                } else {
                    null
                }
            }
        }
    }

    await
        .withAlias("Waiting for transactions confirmations")
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(10000L))
        .until {
            txHashes.all { (chainId, txHash) ->
                blockchainClient(chainId).getTransactionReceipt(txHash)?.isStatusOK ?: false
            }
        }

    return symbolContractAddresses
}

class FixturesBlockchainClient(config: BlockchainClientConfig) : BlockchainClient(config) {
    fun deployMockERC20(tokenName: String, decimals: BigInteger): Address {
        val contract = MockERC20.deploy(
            web3j,
            transactionManager,
            gasProvider,
            "$tokenName Coin",
            tokenName,
            decimals
        ).send()
        return Address(Keys.toChecksumAddress(contract.contractAddress))
    }

    fun asyncBurnERC20(
        tokenContractAddress: Address,
        receiver: Address,
        amount: BigInteger
    ): TxHash =
        sendTransaction(
            tokenContractAddress,
            MockERC20
                .load(tokenContractAddress.value, web3j, transactionManager, gasProvider)
                .burn(receiver.value, amount)
                .encodeFunctionCall(),
            BigInteger.ZERO,
        )

    fun setNativeBalance(address: Address, amount: BigInteger) =
        Request(
            "anvil_setBalance",
            listOf<String>(address.value, Numeric.encodeQuantity(amount)),
            web3jService,
            VoidResponse::class.java
        ).send()
}

