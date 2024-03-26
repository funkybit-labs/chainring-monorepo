package co.chainring.integrationtests.testutils

import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.Symbol
import co.chainring.apps.api.model.WithdrawTx
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
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
    val symbols: List<Symbol>,
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

    fun signWithdraw(symbol: String?, amount: BigInteger, nonceOverride: BigInteger? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getNonce()
        val tx = EIP712Transaction.WithdrawTx(walletKeypair.address, symbol?.let { Address(erc20TokenAddress(symbol)) }, amount, nonce.toLong(), EvmSignature.emptySignature())
        val signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress))
        return CreateWithdrawalApiRequest(WithdrawTx(tx.sender, tx.token, tx.amount, tx.nonce), signature)
    }

    fun getNonce(): BigInteger {
        return exchangeContract.nonces(walletKeypair.address.value).send()
    }

    fun withdrawNative(amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(amount).send()
    }

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClient.depositNative(exchangeContractAddress, amount)
    }

    private fun loadErc20Contract(symbol: String) = blockchainClient.loadERC20Mock(erc20TokenAddress(symbol))

    private fun erc20TokenAddress(symbol: String) =
        symbols.first { it.name == symbol && it.contractAddress != null }.contractAddress!!.value
}
