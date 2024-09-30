package xyz.funkybit.integrationtests.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.Chain
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.apps.ring.EvmDepositHandler
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.evm.EIP712Helper
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.evm.EIP712Transaction
import xyz.funkybit.core.model.evm.TokenAddressAndChain
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration

interface OrderSigner {
    fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market
    fun signOrder(request: CreateOrderApiRequest.Limit, linkedSignerKeyPair: WalletKeyPair? = null): CreateOrderApiRequest.Limit
    fun signOrder(request: CreateOrderApiRequest.BackToBackMarket): CreateOrderApiRequest.BackToBackMarket
}

class Wallet(
    val keyPair: WalletKeyPair.EVM,
    val allChains: List<Chain>,
    val apiClient: ApiClient,
) : OrderSigner {
    val logger = KotlinLogging.logger {}
    companion object {
        operator fun invoke(apiClient: ApiClient): Wallet {
            val config = apiClient.getConfiguration().chains
            // TODO: add bitcoin support
            return Wallet(apiClient.keyPair as WalletKeyPair.EVM, config, apiClient)
        }
    }

    val chains = allChains.filter { it.networkType == NetworkType.Evm }
    private val evmClients = EvmChainManager.evmClientConfigs.map {
        TestEvmClient(EvmChainManager.getEvmClient(it, keyPair.privateKey.toByteArray().toHex()).config)
            .also { evmClient ->
                val chain = chains.first { it.id == evmClient.chainId }
                evmClient.setContractAddress(
                    ContractType.Exchange,
                    chain.contracts.first { it.name == "Exchange" }.address,
                )
            }
    }

    private val evmClientsByChainId = evmClients.associateBy { it.chainId }

    var currentChainId: ChainId = evmClients.first().chainId

    val evmAddress = keyPair.address()

    private val exchangeContractAddressByChainId = chains.associate { it.id to it.contracts.first { it.name == ContractType.Exchange.name }.address }
    private val exchangeContractByChainId = evmClients.associate { it.chainId to it.loadExchangeContract(exchangeContractAddressByChainId.getValue(it.chainId)) }

    fun switchChain(chainId: ChainId) {
        currentChainId = chainId
    }

    fun currentEvmClient(): TestEvmClient {
        return evmClientsByChainId.getValue(currentChainId)
    }

    fun waitForTransactionReceipt(txHash: TxHash): TransactionReceipt =
        currentEvmClient().waitForTransactionReceipt(txHash)

    fun getWalletERC20Balance(symbol: Symbol): BigInteger {
        return loadErc20Contract(symbol.value).balanceOf(evmAddress.value).send()
    }

    fun getWalletERC20Balance(symbol: String): BigInteger {
        return loadErc20Contract(symbol).balanceOf(evmAddress.value).send()
    }

    fun mintERC20AndMine(symbol: String, amount: BigInteger): TransactionReceipt {
        val txHash = sendMintERC20Tx(symbol, amount)
        val evmClient = evmClientsByChainId.getValue(currentChainId)
        evmClient.mine()
        return evmClient.getTransactionReceipt(txHash)!!
    }

    fun mintERC20AndWaitForReceipt(symbol: String, amount: BigInteger): TransactionReceipt =
        waitForTransactionReceipt(sendMintERC20Tx(symbol, amount))

    fun sendMintERC20Tx(symbol: String, amount: BigInteger): TxHash =
        currentEvmClient().sendMintERC20Tx(
            EvmAddress(loadErc20Contract(symbol).contractAddress),
            evmAddress,
            amount,
        )

    fun mintERC20AndMine(assetAmount: AssetAmount): TransactionReceipt =
        mintERC20AndMine(assetAmount.symbol.name, assetAmount.amount.toFundamentalUnits(assetAmount.symbol.decimals))

    fun getWalletNativeBalance(): BigInteger {
        return evmClientsByChainId.getValue(currentChainId).getNativeBalance(evmAddress)
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
        return exchangeContractByChainId.getValue(chainId).balances(evmAddress.value, erc20TokenAddress(symbol, chainId)).send()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContractByChainId.getValue(currentChainId).balances(evmAddress.value, EvmAddress.zero.value).send()
    }

    private fun getExchangeNativeBalance(symbol: String): BigInteger {
        val chainId = chains.first { c -> c.symbols.any { it.name == symbol } }.id
        return exchangeContractByChainId.getValue(chainId).balances(evmAddress.value, EvmAddress.zero.value).send()
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
        val evmClient = evmClientsByChainId.getValue(currentChainId)
        evmClient.mine(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS)
        return evmClient.getTransactionReceipt(txHash)!!
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
        evmClientsByChainId.getValue(currentChainId).sendNativeDepositTx(exchangeContractAddressByChainId.getValue(currentChainId), amount)

    fun sendERC20DepositTx(symbol: String, amount: BigInteger): TxHash {
        val erc20Contract = loadErc20Contract(symbol)

        evmClientsByChainId.getValue(currentChainId).sendTransaction(
            EvmAddress(erc20Contract.contractAddress),
            erc20Contract.approve(exchangeContractAddressByChainId.getValue(currentChainId).toString(), amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )

        // when talking to an RPC node pool, we might have to try this a few times to get the proper nonce
        // try for up to a minute
        val start = Clock.System.now()
        while (Clock.System.now().minus(start) < Duration.parse("1m")) {
            try {
                return evmClientsByChainId.getValue(currentChainId).sendTransaction(
                    EvmAddress(exchangeContractByChainId.getValue(currentChainId).contractAddress),
                    exchangeContractByChainId.getValue(currentChainId).deposit(erc20TokenAddress(symbol)?.value, amount)
                        .encodeFunctionCall(),
                    BigInteger.ZERO,
                )
            } catch (e: Exception) {
                logger.warn(e) { "ERC-20 deposit transaction failed, retrying" }
            }
        }
        throw RuntimeException("Unable to complete ERC-20 deposit")
    }

    fun setLinkedSigner(linkedSigner: String, digest: ByteArray, signature: EvmSignature): TransactionReceipt {
        val evmClient = evmClientsByChainId.getValue(currentChainId)
        val txHash = evmClient.sendTransaction(
            EvmAddress(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).linkSigner(linkedSigner, digest, signature.toByteArray()).encodeFunctionCall(),
            BigInteger.ZERO,
        )
        evmClient.mine()
        return evmClient.getTransactionReceipt(txHash)!!
    }

    fun removeLinkedSigner(): TransactionReceipt {
        val evmClient = evmClientsByChainId.getValue(currentChainId)
        val txHash = evmClient.sendTransaction(
            EvmAddress(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).removeLinkedSigner().encodeFunctionCall(),
            BigInteger.ZERO,
        )
        evmClient.mine()
        return evmClient.getTransactionReceipt(txHash)!!
    }

    fun getLinkedSigner(chainId: ChainId): EvmAddress {
        return EvmAddress(Keys.toChecksumAddress(exchangeContractByChainId.getValue(chainId).linkedSigners(evmAddress.value).send()))
    }

    fun signWithdraw(symbol: String, amount: BigInteger, nonceOverride: Long? = null, linkedSignerKeyPair: WalletKeyPair? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx = EIP712Transaction.WithdrawTx(
            evmAddress,
            TokenAddressAndChain(erc20TokenAddress(symbol) ?: EvmAddress.zero, this.currentChainId),
            amount,
            nonce,
            amount == BigInteger.ZERO,
            EvmSignature.emptySignature(),
        )
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            evmClientsByChainId.getValue(currentChainId).signData(
                EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
                (linkedSignerKeyPair as? WalletKeyPair.EVM)?.ecKeyPair,
            ),
        )
    }

    override fun signOrder(request: CreateOrderApiRequest.Limit, linkedSignerKeyPair: WalletKeyPair?): CreateOrderApiRequest.Limit =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce, linkedSignerKeyPair),
            verifyingChainId = this.currentChainId,
        )

    private fun chainId(symbol: SymbolInfo) = allChains.first {
        it.symbols.contains(symbol)
    }.id

    override fun signOrder(request: CreateOrderApiRequest.BackToBackMarket): CreateOrderApiRequest.BackToBackMarket {
        val market1Symbols = marketSymbols(request.marketId)
        val market2Symbols = marketSymbols(request.secondMarketId)
        val(inputSymbol, bridgeSymbol, outputSymbol) = if (listOf(market2Symbols.first.name, market2Symbols.second.name).contains(market1Symbols.first.name)) {
            if (market2Symbols.first.name == market1Symbols.first.name) {
                Triple(market1Symbols.second, market1Symbols.first, market2Symbols.second)
            } else {
                Triple(market1Symbols.second, market1Symbols.first, market2Symbols.first)
            }
        } else {
            if (market2Symbols.first.name == market1Symbols.second.name) {
                Triple(market1Symbols.first, market1Symbols.second, market2Symbols.second)
            } else {
                Triple(market1Symbols.first, market1Symbols.second, market2Symbols.first)
            }
        }

        val tx = EIP712Transaction.Order(
            evmAddress,
            baseChainId = chainId(inputSymbol),
            baseToken = inputSymbol.contractAddress ?: EvmAddress.zero,
            quoteChainId = chainId(outputSymbol),
            quoteToken = outputSymbol.contractAddress ?: EvmAddress.zero,
            amount = request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = evmClientsByChainId.getValue(currentChainId).signData(
                EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
            ),
            verifyingChainId = this.currentChainId,
        )
    }

    override fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val tx = EIP712Transaction.Order(
            evmAddress,
            baseChainId = chainId(baseSymbol),
            baseToken = baseSymbol.contractAddress ?: EvmAddress.zero,
            quoteChainId = chainId(quoteSymbol),
            quoteToken = quoteSymbol.contractAddress ?: EvmAddress.zero,
            amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = evmClientsByChainId.getValue(currentChainId).signData(
                EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
            ),
            verifyingChainId = this.currentChainId,
        )
    }

    fun signCancelOrder(request: CancelOrderApiRequest): CancelOrderApiRequest {
        val tx = EIP712Transaction.CancelOrder(
            evmAddress,
            request.marketId,
            if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            BigInteger(1, request.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = evmClientsByChainId.getValue(currentChainId).signData(
                EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
            ),
            verifyingChainId = this.currentChainId,
        )
    }

    fun rollbackSettlement() {
        exchangeContractByChainId.getValue(currentChainId).rollbackBatch().sendAsync()
    }

    private fun limitOrderEip712TxSignature(marketId: MarketId, amount: OrderAmount, price: BigDecimal, side: OrderSide, nonce: String, linkedSignerKeyPair: WalletKeyPair? = null): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(marketId)
        val tx = EIP712Transaction.Order(
            evmAddress,
            baseChainId = chainId(baseSymbol),
            baseToken = baseSymbol.contractAddress ?: EvmAddress.zero,
            quoteChainId = chainId(quoteSymbol),
            quoteToken = quoteSymbol.contractAddress ?: EvmAddress.zero,
            amount = if (side == OrderSide.Buy) amount else amount.negate(),
            price = price.toFundamentalUnits(quoteSymbol.decimals),
            nonce = BigInteger(1, nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return evmClientsByChainId.getValue(currentChainId).signData(
            EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
            (linkedSignerKeyPair as? WalletKeyPair.EVM)?.ecKeyPair,
        )
    }

    private fun getWithdrawalNonce(): Long {
        return System.currentTimeMillis()
    }

    private fun loadErc20Contract(symbol: String) = evmClientsByChainId.getValue(currentChainId).loadERC20Mock(erc20TokenAddress(symbol)!!.value)

    private fun erc20TokenAddress(symbol: String): EvmAddress? =
        chains.first { it.id == currentChainId }.symbols.firstOrNull { (it.name == symbol || it.name == "$symbol:$currentChainId") && it.contractAddress != null }?.contractAddress as? EvmAddress

    private fun erc20TokenAddress(symbol: String, chainId: ChainId): String? =
        chains.first { it.id == chainId }.symbols.firstOrNull { (it.name == symbol || it.name == "$symbol:$currentChainId") && it.contractAddress != null }?.contractAddress?.toString()

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    allChains.map { it.symbols.filter { s -> s.name == base } }.flatten().first(),
                    allChains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }

    fun requestSovereignWithdrawalAndMine(symbol: String, amount: BigInteger): TxHash {
        val evmClient = evmClientsByChainId.getValue(currentChainId)

        return evmClient.sovereignWithdrawal(
            senderCredentials = keyPair.credentials,
            tokenContractAddress = erc20TokenAddress(symbol) ?: EvmAddress.zero,
            amount = amount,
        ).also { evmClient.mine() }
    }
}
