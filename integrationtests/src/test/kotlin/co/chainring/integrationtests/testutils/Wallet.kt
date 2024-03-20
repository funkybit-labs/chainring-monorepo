package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.ERC20Token
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigInteger

data class TestWalletKeypair(
    val privateKeyHex: String,
    val address: Address,
)

class Wallet(
    val walletKeypair: TestWalletKeypair,
    val contracts: List<DeployedContract>,
    val erc20Tokens: List<ERC20Token>,
) {

    private val blockchainClient = TestBlockchainClient(BlockchainClientConfig().copy(privateKeyHex = walletKeypair.privateKeyHex))
    private val exchangeContractAddress = contracts.first { it.name == ContractType.Exchange.name }.address
    private val exchangeContract: Exchange = blockchainClient.loadExchangeContract(exchangeContractAddress)

    fun getWalletERC20Balance(symbol: String): BigInteger {
        return loadErc20Contract(symbol).balanceOf(walletKeypair.address.value).send()
    }

    fun mintERC20(symbol: String, amount: BigInteger) {
        loadErc20Contract(symbol).mint(walletKeypair.address.value, amount).send()
    }

    fun getWalletNativeBalance(): BigInteger {
        return blockchainClient.getNativeBalance(walletKeypair.address)
    }

    fun getExchangeERC20Balance(symbol: String): BigInteger {
        return exchangeContract.balances(walletKeypair.address.value, erc20Token(symbol).address.value).send()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContract.nativeBalances(walletKeypair.address.value).send()
    }

    fun depositERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        loadErc20Contract(symbol).approve(exchangeContractAddress.value, amount).send()
        return exchangeContract.deposit(erc20Token(symbol).address.value, amount).send()
    }

    fun withdrawERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(erc20Token(symbol).address.value, amount).send()
    }

    fun withdrawNative(amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(amount).send()
    }

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClient.depositNative(exchangeContractAddress, amount)
    }

    private fun loadErc20Contract(symbol: String) = blockchainClient.loadERC20Mock(erc20Token(symbol).address.value)

    private fun erc20Token(symbol: String) = erc20Tokens.first { it.symbol.value == symbol }
}