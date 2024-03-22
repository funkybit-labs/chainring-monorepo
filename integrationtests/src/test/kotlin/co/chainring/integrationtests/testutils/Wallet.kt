package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.Symbol
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigInteger

data class TestWalletKeypair(
    val privateKeyHex: String,
    val address: Address,
)

class Wallet(
    val blockchainClient: TestBlockchainClient,
    val walletKeypair: TestWalletKeypair,
    val contracts: List<DeployedContract>,
    val erc20Tokens: List<Symbol>,
) {

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
        return exchangeContract.balances(walletKeypair.address.value, erc20TokenAddress(symbol)).send()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContract.nativeBalances(walletKeypair.address.value).send()
    }

    fun depositERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        loadErc20Contract(symbol).approve(exchangeContractAddress.value, amount).send()
        return exchangeContract.deposit(erc20TokenAddress(symbol), amount).send()
    }

    fun withdrawERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(erc20TokenAddress(symbol), amount).send()
    }

    fun withdrawNative(amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(amount).send()
    }

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClient.depositNative(exchangeContractAddress, amount)
    }

    private fun loadErc20Contract(symbol: String) = blockchainClient.loadERC20Mock(erc20TokenAddress(symbol))

    private fun erc20TokenAddress(symbol: String) =
        erc20Tokens.first { it.name == symbol && it.contractAddress != null }.contractAddress!!.value
}
