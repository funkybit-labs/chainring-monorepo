package xyz.funkybit.tasks

import xyz.funkybit.contracts.generated.MockERC20
import xyz.funkybit.core.blockchain.evm.EvmClient
import xyz.funkybit.core.blockchain.evm.EvmClientConfig
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.connect
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.tasks.fixtures.Fixtures
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
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.utils.fromFundamentalUnits
import java.time.Duration

data class SymbolContractAddress(
    val symbolId: SymbolId,
    val address: Address
)

val evmClients = EvmChainManager.evmClientConfigs.map {
    FixturesEvmClient(it.copy(enableWeb3jLogging = false))
}
val evmClientsByChainId = evmClients.associateBy { it.chainId }

fun evmClient(chainId: ChainId) = evmClientsByChainId.getValue(chainId)

fun seedBlockchain(fixtures: Fixtures): List<SymbolContractAddress> {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db

    // seed the fee payer
    fixtures.chains.firstOrNull { it.id == bitcoinConfig.chainId }?.let {
        // fund the fee payer
        airdropBtcToFeePayer()
    }

    val symbolEntities = transaction { SymbolEntity.all().toList() }

    val symbolContractAddresses = fixtures.symbols.mapNotNull { symbol ->
        if (symbol.isNative) {
            null
        } else {
            val contractAddress = symbolEntities.firstOrNull { it.id.value == symbol.id }?.contractAddress ?: run {
                val contractAddress = evmClient(symbol.chainId).deployMockERC20(
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
            when (wallet.address) {
                is BitcoinAddress -> {
                    if (balance == BigInteger.ZERO) {
                        null
                    } else {
                        val symbol = fixtures.symbols.first { it.id == symbolId }
                        println("Setting ${symbol.name} balance for ${wallet.address.value} to $balance")
                        airdropBtcToAddress(wallet.address, balance)?.let { txHash ->
                            Pair(bitcoinConfig.chainId, txHash)
                        }
                    }
                }
                is EvmAddress -> {
                    val symbol = fixtures.symbols.first { it.id == symbolId }
                    val evmClient = evmClient(symbol.chainId)

                    println("Setting ${symbol.name} balance for ${wallet.address.value} to $balance")

                    if (symbol.isNative) {
                        evmClient.setNativeBalance(wallet.address, balance)
                        null
                    } else {
                        val symbolContractAddress = symbolContractAddresses.first { it.symbolId == symbolId }.address
                        when (symbolContractAddress) {
                            is BitcoinAddress -> null // TODO get bitcoin token balance
                            is EvmAddress -> {
                                val currentBalance =
                                    evmClient.getERC20Balance(symbolContractAddress, wallet.address)

                                if (currentBalance < balance) {
                                    Pair(
                                        symbol.chainId,
                                        evmClient.sendMintERC20Tx(
                                            symbolContractAddress,
                                            wallet.address,
                                            balance - currentBalance
                                        )
                                    )
                                } else if (currentBalance > balance) {
                                    Pair(
                                        symbol.chainId,
                                        evmClient.asyncBurnERC20(
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
                if (chainId == bitcoinConfig.chainId) {
                    MempoolSpaceClient.getTransaction(txHash)?.status?.confirmed == true
                } else {
                    evmClient(chainId).getTransactionReceipt(txHash)?.isStatusOK ?: false
                }
            }
        }

    return symbolContractAddresses
}

private fun airdropBtcToFeePayer() {
    try {
        (0 .. 5).forEach { _ ->
            BitcoinClient.sendToAddress(
                bitcoinConfig.feePayerAddress,
                BigInteger("8000"),
            )
        }
    } catch (e: Exception) {
        println("failed to airdrop to fee payer")
    }
}

private fun airdropBtcToAddress(address: BitcoinAddress, amount: BigInteger): TxHash? {
    try {
        val airdropAmount = maxOf(amount, BigInteger.valueOf(5000L))
        println("Air-dropping ${airdropAmount.fromFundamentalUnits(8).toPlainString()} BTC to $address")
        return BitcoinClient.sendToAddress(address, airdropAmount)
    } catch (e: Exception) {
        println("Failed to airdrop BTC to $address")
    }
    return null
}

class FixturesEvmClient(config: EvmClientConfig) : EvmClient(config) {
    fun deployMockERC20(tokenName: String, decimals: BigInteger): EvmAddress {
        val contract = MockERC20.deploy(
            web3j,
            transactionManager,
            gasProvider,
            "$tokenName Coin",
            tokenName,
            decimals
        ).send()
        return EvmAddress(Keys.toChecksumAddress(contract.contractAddress))
    }

    fun asyncBurnERC20(
        tokenContractAddress: EvmAddress,
        receiver: EvmAddress,
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

    fun setNativeBalance(address: EvmAddress, amount: BigInteger) =
        Request(
            "anvil_setBalance",
            listOf<String>(address.value, Numeric.encodeQuantity(amount)),
            web3jService,
            VoidResponse::class.java
        ).send()
}

