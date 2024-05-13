package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.DeployedContract
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

class Wallet(
    val walletKeypair: ECKeyPair,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
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
        return exchangeContract.balances(address.value, Address.zero.value).sendAndWaitForConfirmation()
    }

    fun asyncDepositNative(amount: BigInteger): TxHash =
        blockchainClient.asyncDepositNative(exchangeContractAddress, amount)

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClient.depositNative(exchangeContractAddress, amount)
    }

    fun asyncDepositERC20(symbol: String, amount: BigInteger): TxHash {
        val erc20Contract = loadErc20Contract(symbol)

        blockchainClient.sendTransaction(
            Address(erc20Contract.contractAddress),
            erc20Contract.approve(exchangeContractAddress.value, amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )

        return blockchainClient.sendTransaction(
            Address(exchangeContract.contractAddress),
            exchangeContract.deposit(erc20TokenAddress(symbol), amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )
    }

    fun depositERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        loadErc20Contract(symbol).approve(exchangeContractAddress.value, amount).sendAndWaitForConfirmation()
        return exchangeContract.deposit(erc20TokenAddress(symbol), amount).sendAndWaitForConfirmation()
    }

    fun signWithdraw(symbol: String, amount: BigInteger, nonceOverride: Long? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx = EIP712Transaction.WithdrawTx(address, erc20TokenAddress(symbol)?.let { Address(it) }, amount, nonce, EvmSignature.emptySignature())
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)),
        )
    }

    fun signOrder(request: CreateOrderApiRequest.Limit): CreateOrderApiRequest.Limit =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce),
        )

    fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val tx = EIP712Transaction.Order(
            address,
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return request.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
    }

    fun signOrder(request: UpdateOrderApiRequest): UpdateOrderApiRequest =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce),
        )

    fun signCancelOrder(request: CancelOrderApiRequest): CancelOrderApiRequest {
        val tx = EIP712Transaction.CancelOrder(
            address,
            request.marketId,
            if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            BigInteger(1, request.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return request.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
    }

    private fun limitOrderEip712TxSignature(marketId: MarketId, amount: BigInteger, price: BigDecimal, side: OrderSide, nonce: String): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(marketId)
        val tx = EIP712Transaction.Order(
            address,
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (side == OrderSide.Buy) amount else amount.negate(),
            price = price.toFundamentalUnits(quoteSymbol.decimals),
            nonce = BigInteger(1, nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress))
    }

    private fun getWithdrawalNonce(): Long {
        return System.currentTimeMillis()
    }

    fun formatAmount(amount: String, symbol: String): BigInteger {
        return BigDecimal(amount).toFundamentalUnits(decimals(symbol))
    }

    fun decimals(symbol: String): Int = symbols.first { it.name == symbol }.decimals.toInt()

    private fun loadErc20Contract(symbol: String) = blockchainClient.loadERC20Mock(erc20TokenAddress(symbol)!!)

    private fun erc20TokenAddress(symbol: String) =
        symbols.firstOrNull { it.name == symbol && it.contractAddress != null }?.contractAddress?.value

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(symbols.first { it.name == base }, symbols.first { it.name == quote })
            }
}
