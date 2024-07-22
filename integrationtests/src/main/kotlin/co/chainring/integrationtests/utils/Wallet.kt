package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.Chain
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

class Wallet(
    val walletKeypair: ECKeyPair,
    val chains: List<Chain>,
    val apiClient: ApiClient,
) {

    companion object {
        operator fun invoke(apiClient: ApiClient): Wallet {
            val config = apiClient.getConfiguration().chains
            return Wallet(apiClient.ecKeyPair, config, apiClient)
        }
    }
    private val blockchainClients = ChainManager.blockchainConfigs.map {
        TestBlockchainClient(ChainManager.getBlockchainClient(it, walletKeypair.privateKey.toByteArray().toHex()).config)
            .also { blockchainClient ->
                val chain = chains.first { it.id == blockchainClient.chainId }
                blockchainClient.setContractAddress(
                    ContractType.Exchange,
                    chain.contracts.first { it.name == "Exchange" }.address,
                )
            }
    }

    private val blockchainClientsByChainId = blockchainClients.associateBy { it.chainId }

    var currentChainId: ChainId = blockchainClients.first().chainId

    val address = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(walletKeypair)))

    private val exchangeContractAddressByChainId = chains.associate { it.id to it.contracts.first { it.name == ContractType.Exchange.name }.address }
    private val exchangeContractByChainId = blockchainClients.associate { it.chainId to it.loadExchangeContract(exchangeContractAddressByChainId.getValue(it.chainId)) }

    fun switchChain(chainId: ChainId) {
        currentChainId = chainId
    }

    fun currentBlockchainClient(): TestBlockchainClient {
        return blockchainClientsByChainId.getValue(currentChainId)
    }

    fun waitForTransactionReceipt(txHash: TxHash): TransactionReceipt =
        currentBlockchainClient().waitForTransactionReceipt(txHash)

    fun getWalletERC20Balance(symbol: Symbol): BigInteger {
        return loadErc20Contract(symbol.value).balanceOf(address.value).send()
    }

    fun getWalletERC20Balance(symbol: String): BigInteger {
        return loadErc20Contract(symbol).balanceOf(address.value).send()
    }

    fun mintERC20AndMine(symbol: String, amount: BigInteger): TransactionReceipt {
        val txHash = sendMintERC20Tx(symbol, amount)
        val blockchainClient = blockchainClientsByChainId.getValue(currentChainId)
        blockchainClient.mine()
        return blockchainClient.getTransactionReceipt(txHash)!!
    }

    fun mintERC20AndWaitForReceipt(symbol: String, amount: BigInteger): TransactionReceipt =
        waitForTransactionReceipt(sendMintERC20Tx(symbol, amount))

    fun sendMintERC20Tx(symbol: String, amount: BigInteger): TxHash =
        currentBlockchainClient().sendMintERC20Tx(
            Address(loadErc20Contract(symbol).contractAddress),
            address,
            amount,
        )

    fun mintERC20AndMine(assetAmount: AssetAmount): TransactionReceipt =
        mintERC20AndMine(assetAmount.symbol.name, assetAmount.amount.toFundamentalUnits(assetAmount.symbol.decimals))

    fun getWalletNativeBalance(): BigInteger {
        return blockchainClientsByChainId.getValue(currentChainId).getNativeBalance(address)
    }

    fun getWalletBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            if (symbol.contractAddress == null) {
                getWalletNativeBalance()
            } else {
                getWalletERC20Balance(symbol.name)
            }.fromFundamentalUnits(symbol.decimals),
        )

    fun getExchangeBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            if (symbol.contractAddress == null) {
                getExchangeNativeBalance(symbol.name)
            } else {
                getExchangeERC20Balance(symbol.name)
            }.fromFundamentalUnits(symbol.decimals),
        )

    fun getExchangeERC20Balance(symbol: String): BigInteger {
        val chainId = chains.first { c -> c.symbols.any { it.name == symbol } }.id
        return exchangeContractByChainId.getValue(chainId).balances(address.value, erc20TokenAddress(symbol, chainId)).send()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContractByChainId.getValue(currentChainId).balances(address.value, Address.zero.value).send()
    }

    private fun getExchangeNativeBalance(symbol: String): BigInteger {
        val chainId = chains.first { c -> c.symbols.any { it.name == symbol } }.id
        return exchangeContractByChainId.getValue(chainId).balances(address.value, Address.zero.value).send()
    }

    fun depositAndMine(assetAmount: AssetAmount): TransactionReceipt {
        val txHash = sendDepositTx(assetAmount)
        apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = Symbol(assetAmount.symbol.name),
                amount = assetAmount.inFundamentalUnits,
                txHash = txHash,
            ),
        )
        val blockchainClient = blockchainClientsByChainId.getValue(currentChainId)
        blockchainClient.mine()
        return blockchainClient.getTransactionReceipt(txHash)!!
    }

    fun depositAndWaitForTxReceipt(assetAmount: AssetAmount): TransactionReceipt =
        waitForTransactionReceipt(sendDepositTx(assetAmount))

    fun sendDepositTx(assetAmount: AssetAmount): TxHash {
        return if (assetAmount.symbol.contractAddress == null) {
            sendNativeDepositTx(assetAmount.amount.toFundamentalUnits(assetAmount.symbol.decimals))
        } else {
            sendERC20DepositTx(assetAmount.symbol.name, assetAmount.amount.toFundamentalUnits(assetAmount.symbol.decimals))
        }
    }

    fun sendNativeDepositTx(amount: BigInteger): TxHash =
        blockchainClientsByChainId.getValue(currentChainId).sendNativeDepositTx(exchangeContractAddressByChainId.getValue(currentChainId), amount)

    fun sendERC20DepositTx(symbol: String, amount: BigInteger): TxHash {
        val erc20Contract = loadErc20Contract(symbol)

        blockchainClientsByChainId.getValue(currentChainId).sendTransaction(
            Address(erc20Contract.contractAddress),
            erc20Contract.approve(exchangeContractAddressByChainId.getValue(currentChainId).value, amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )

        return blockchainClientsByChainId.getValue(currentChainId).sendTransaction(
            Address(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).deposit(erc20TokenAddress(symbol)?.value, amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )
    }

    fun setLinkedSigner(linkedSigner: String, digest: ByteArray, signature: EvmSignature): TransactionReceipt {
        val blockchainClient = blockchainClientsByChainId.getValue(currentChainId)
        val txHash = blockchainClient.sendTransaction(
            Address(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).linkSigner(linkedSigner, digest, signature.toByteArray()).encodeFunctionCall(),
            BigInteger.ZERO,
        )
        blockchainClient.mine()
        return blockchainClient.getTransactionReceipt(txHash)!!
    }

    fun removeLinkedSigner(): TransactionReceipt {
        val blockchainClient = blockchainClientsByChainId.getValue(currentChainId)
        val txHash = blockchainClient.sendTransaction(
            Address(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).removeLinkedSigner().encodeFunctionCall(),
            BigInteger.ZERO,
        )
        blockchainClient.mine()
        return blockchainClient.getTransactionReceipt(txHash)!!
    }

    fun getLinkedSigner(chainId: ChainId): Address {
        return Address(Keys.toChecksumAddress(exchangeContractByChainId.getValue(chainId).linkedSigners(address.value).send()))
    }

    fun signWithdraw(symbol: String, amount: BigInteger, nonceOverride: Long? = null, linkedSignerEcKeyPair: ECKeyPair? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx = EIP712Transaction.WithdrawTx(
            address,
            TokenAddressAndChain(erc20TokenAddress(symbol) ?: Address.zero, this.currentChainId),
            amount,
            nonce,
            amount == BigInteger.ZERO,
            EvmSignature.emptySignature(),
        )
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)), linkedSignerEcKeyPair),
        )
    }

    fun signOrder(request: CreateOrderApiRequest.Limit, linkedSignerEcKeyPair: ECKeyPair? = null): CreateOrderApiRequest.Limit =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce, linkedSignerEcKeyPair),
            verifyingChainId = this.currentChainId,
        )

    private fun chainId(symbol: SymbolInfo) = chains.first {
        it.symbols.contains(symbol)
    }.id

    fun signOrder(request: CreateOrderApiRequest.BackToBackMarket): CreateOrderApiRequest.BackToBackMarket {
        val (baseSymbol, _) = marketSymbols(request.marketId)
        val (_, quoteSymbol) = marketSymbols(request.secondMarketId)

        val tx = EIP712Transaction.Order(
            address,
            baseChainId = chainId(baseSymbol),
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteChainId = chainId(quoteSymbol),
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
            verifyingChainId = this.currentChainId,
        )
    }

    fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val tx = EIP712Transaction.Order(
            address,
            baseChainId = chainId(baseSymbol),
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteChainId = chainId(quoteSymbol),
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
            verifyingChainId = this.currentChainId,
        )
    }

    fun signCancelOrder(request: CancelOrderApiRequest): CancelOrderApiRequest {
        val tx = EIP712Transaction.CancelOrder(
            address,
            request.marketId,
            if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            BigInteger(1, request.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
            verifyingChainId = this.currentChainId,
        )
    }

    fun rollbackSettlement() {
        exchangeContractByChainId.getValue(currentChainId).rollbackBatch().sendAsync()
    }

    private fun limitOrderEip712TxSignature(marketId: MarketId, amount: OrderAmount, price: BigDecimal, side: OrderSide, nonce: String, linkedSignerEcKeyPair: ECKeyPair? = null): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(marketId)
        val tx = EIP712Transaction.Order(
            address,
            baseChainId = chainId(baseSymbol),
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteChainId = chainId(quoteSymbol),
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (side == OrderSide.Buy) amount else amount.negate(),
            price = price.toFundamentalUnits(quoteSymbol.decimals),
            nonce = BigInteger(1, nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)), linkedSignerEcKeyPair)
    }

    private fun getWithdrawalNonce(): Long {
        return System.currentTimeMillis()
    }

    private fun loadErc20Contract(symbol: String) = blockchainClientsByChainId.getValue(currentChainId).loadERC20Mock(erc20TokenAddress(symbol)!!.value)

    private fun erc20TokenAddress(symbol: String): Address? =
        chains.first { it.id == currentChainId }.symbols.firstOrNull { (it.name == symbol || it.name == "$symbol:$currentChainId") && it.contractAddress != null }?.contractAddress

    private fun erc20TokenAddress(symbol: String, chainId: ChainId): String? =
        chains.first { it.id == chainId }.symbols.firstOrNull { (it.name == symbol || it.name == "$symbol:$currentChainId") && it.contractAddress != null }?.contractAddress?.value

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    chains.map { it.symbols.filter { s -> s.name == base } }.flatten().first(),
                    chains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }

    fun requestSovereignWithdrawalAndMine(symbol: String, amount: BigInteger): TxHash {
        val blockchainClient = blockchainClientsByChainId.getValue(currentChainId)

        return blockchainClient.sovereignWithdrawal(
            senderCredentials = Credentials.create(walletKeypair),
            tokenContractAddress = erc20TokenAddress(symbol) ?: Address.zero,
            amount = amount,
        ).also { blockchainClient.mine() }
    }
}
