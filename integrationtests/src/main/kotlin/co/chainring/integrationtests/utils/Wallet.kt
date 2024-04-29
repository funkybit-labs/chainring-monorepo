package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.Symbol
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.WithdrawTx
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

class Wallet(
    val walletKeypair: ECKeyPair,
    val contracts: List<DeployedContract>,
    val symbols: List<Symbol>,
) {

    companion object {
        operator fun invoke(apiClient: ApiClient): Wallet {
            val config = apiClient.getConfiguration().chains.first()
            return Wallet(apiClient.ecKeyPair, config.contracts, config.symbols)
        }
    }

    val blockchainClient = TestBlockchainClient(BlockchainClientConfig().copy(privateKeyHex = walletKeypair.privateKey.toByteArray().toHex()))
    val address = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(walletKeypair)))

    private val exchangeContractAddress = contracts.first { it.name == ContractType.Exchange.name }.address
    private val exchangeContract: Exchange = blockchainClient.loadExchangeContract(exchangeContractAddress)

    fun getWalletERC20Balance(symbol: String): BigInteger {
        return loadErc20Contract(symbol).balanceOf(address.value).send()
    }

    fun mintERC20(symbol: String, amount: BigInteger) {
        loadErc20Contract(symbol).mint(address.value, amount).sendAndWaitForConfirmation()
    }

    fun getWalletNativeBalance(): BigInteger {
        return blockchainClient.getNativeBalance(address)
    }

    fun getExchangeERC20Balance(symbol: String): BigInteger {
        return exchangeContract.balances(address.value, erc20TokenAddress(symbol)).sendAndWaitForConfirmation()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContract.nativeBalances(address.value).sendAndWaitForConfirmation()
    }

    fun depositERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        loadErc20Contract(symbol).approve(exchangeContractAddress.value, amount).sendAndWaitForConfirmation()
        return exchangeContract.deposit(erc20TokenAddress(symbol), amount).sendAndWaitForConfirmation()
    }

    fun withdrawERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(erc20TokenAddress(symbol), amount).sendAndWaitForConfirmation()
    }

    fun signWithdraw(symbol: String?, amount: BigInteger, nonceOverride: Long? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx = EIP712Transaction.WithdrawTx(address, symbol?.let { Address(erc20TokenAddress(symbol)) }, amount, nonce, EvmSignature.emptySignature())
        val signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress))
        return CreateWithdrawalApiRequest(WithdrawTx(tx.sender, tx.token, tx.amount, tx.nonce), signature)
    }

    fun signOrder(request: CreateOrderApiRequest.Limit): CreateOrderApiRequest.Limit {
        val (baseSymbol, quoteSymbol) = request.marketId.value.split("/")
        val tx = request.toEip712Transaction(
            address,
            symbols.first { it.name == baseSymbol },
            symbols.first { it.name == quoteSymbol },
        )
        return request.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
    }

    fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = request.marketId.value.split("/")
        val tx = request.toEip712Transaction(
            address,
            symbols.first { it.name == baseSymbol },
            symbols.first { it.name == quoteSymbol },
        )
        return request.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
    }

    fun signOrder(request: UpdateOrderApiRequest.Limit): UpdateOrderApiRequest.Limit {
        val (baseSymbol, quoteSymbol) = request.marketId.value.split("/")
        val tx = request.toEip712Transaction(
            address,
            symbols.first { it.name == baseSymbol },
            symbols.first { it.name == quoteSymbol },
        )
        return request.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
    }

    private fun getWithdrawalNonce(): Long {
        return System.currentTimeMillis()
    }

    fun withdrawNative(amount: BigInteger): TransactionReceipt {
        return exchangeContract.withdraw(amount).sendAndWaitForConfirmation()
    }

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClient.depositNative(exchangeContractAddress, amount)
    }

    fun formatAmount(amount: String, symbol: String): BigInteger {
        return BigDecimal(amount).toFundamentalUnits(decimals(symbol))
    }

    fun decimals(symbol: String): Int = symbols.first { it.name == symbol }.decimals.toInt()

    private fun loadErc20Contract(symbol: String) = blockchainClient.loadERC20Mock(erc20TokenAddress(symbol))

    private fun erc20TokenAddress(symbol: String) =
        symbols.first { it.name == symbol && it.contractAddress != null }.contractAddress!!.value
}
