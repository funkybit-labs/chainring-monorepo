package co.chainring.telegrambot.app

import arrow.core.Either
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.apps.api.model.FaucetApiResponse
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderAmount
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.client.rest.ApiCallFailure
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.client.ws.subscribe
import co.chainring.core.client.ws.unsubscribe
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds

data class ExchangeBalance(
    val symbol: Symbol,
    val total: BigDecimal,
    val available: BigDecimal,
)

data class WalletBalance(
    val symbol: Symbol,
    val balance: BigDecimal,
)

data class SwapEstimation(
    val from: Symbol,
    val fromAmount: BigDecimal,
    val to: Symbol,
    val toAmount: BigDecimal,
    val price: BigDecimal,
    val market: Market,
)

class BotSessionCurrentWallet(
    val wallet: TelegramBotUserWalletEntity,
) {
    private val logger = KotlinLogging.logger { }
    private val ecKeyPair = Credentials.create(wallet.encryptedPrivateKey.decrypt()).ecKeyPair
    private val apiClient = ApiClient(ecKeyPair)
    val config = apiClient.tryGetConfiguration().getOrNull()
        ?: throw Exception("Unable to retrieve config")

    private val chainBySymbol = config.chains.map { chain ->
        chain.symbols.associate { Symbol(it.name) to chain.id }
    }.flatMap { map -> map.entries }.associate(Map.Entry<Symbol, ChainId>::toPair)
    val symbolInfoBySymbol = config.chains.map { chain ->
        chain.symbols.associateBy { Symbol(it.name) }
    }.flatMap { map -> map.entries }.associate(Map.Entry<Symbol, SymbolInfo>::toPair)
    private val blockchainClientsByChainId = ChainManager.blockchainConfigs.associate { blockchainConfig ->
        val blockchainClient = ChainManager.getBlockchainClient(
            blockchainConfig,
            privateKeyHex = ecKeyPair.privateKey.toByteArray().toHex(),
        )
        blockchainClient.chainId to blockchainClient.also { client ->
            client.setContractAddress(
                ContractType.Exchange,
                config.chains.first { it.id == client.chainId }.contracts.first { it.name == ContractType.Exchange.name }.address,
            )
        }
    }
    private val marketPrices = mutableMapOf<MarketId, BigDecimal>()
    val walletAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(ecKeyPair)))

    private var websocket: WebSocket? = null
    private val websocketSubscriptionTopics = config.markets.map { market -> SubscriptionTopic.Prices(market.id, OHLCDuration.P5M) }

    fun start() {
        connectWebsocket()
    }

    fun stop() {
        try {
            websocket?.also { ws ->
                websocketSubscriptionTopics.forEach(ws::unsubscribe)
                ws.close(1001, "Going away")
            }
        } catch (e: Exception) {
            logger.error(e) { "failed to stop bot wallet" }
        }
    }

    private fun connectWebsocket() {
        logger.debug { "Connecting websocket" }
        val cooldownBeforeReconnecting = 1.seconds

        websocket = apiClient.newWebSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.debug { "Websocket connected" }
                websocketSubscriptionTopics.forEach(webSocket::subscribe)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val decodedMessage = Json.decodeFromString<OutgoingWSMessage>(text) as OutgoingWSMessage.Publish
                    handleWebsocketMessage(decodedMessage.data)
                } catch (e: Exception) {
                    logger.error(e) { "Error while handling websocket message" }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectWebsocket()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.warn(t) { "Websocket error, reconnecting in $cooldownBeforeReconnecting" }
                webSocket.cancel()
                Thread.sleep(cooldownBeforeReconnecting.inWholeMilliseconds)
                connectWebsocket()
            }
        })
    }

    private fun handleWebsocketMessage(message: Publishable) {
        when (message) {
            is Prices -> {
                message.ohlc.lastOrNull()?.let {
                    marketPrices[message.market] = it.close.toBigDecimal()
                }
            }
            else -> {}
        }
    }

    fun getExchangeBalances(): List<ExchangeBalance> {
        val balances = apiClient
            .getBalances().balances
            .map {
                ExchangeBalance(
                    it.symbol,
                    total = it.total.fromFundamentalUnits(decimals(it.symbol)),
                    available = it.available.fromFundamentalUnits(decimals(it.symbol)),
                )
            }
            .associateBy { it.symbol }

        return symbols().map {
            balances.getOrDefault(it, ExchangeBalance(it, BigDecimal.ZERO.setScale(decimals(it)), BigDecimal.ZERO.setScale(decimals(it))))
        }
    }

    fun getWalletBalances(): List<WalletBalance> {
        return symbols().map { symbol ->
            val blockchainClient = blockchainClient(symbol)
            val balance = contractAddress(symbol)?.let {
                blockchainClient.getERC20Balance(it, walletAddress).fromFundamentalUnits(decimals(symbol))
            } ?: blockchainClient.getNativeBalance(walletAddress).fromFundamentalUnits(decimals(symbol))

            WalletBalance(symbol, balance)
        }
    }

    fun getWalletBalance(symbol: Symbol): BigDecimal {
        val blockchainClient = blockchainClient(symbol)
        return contractAddress(symbol)?.let {
            blockchainClient.getERC20Balance(it, walletAddress).fromFundamentalUnits(decimals(symbol))
        } ?: blockchainClient.getNativeBalance(walletAddress).fromFundamentalUnits(decimals(symbol))
    }

    fun getExchangeAvailableBalance(symbol: Symbol): BigDecimal =
        apiClient
            .getBalances().balances
            .find { it.symbol == symbol }
            ?.available?.fromFundamentalUnits(decimals(symbol))
            ?: BigDecimal.ZERO.setScale(decimals(symbol))

    private fun getMarketPrice(marketId: MarketId): BigDecimal {
        return marketPrices.getValue(marketId)
    }

    fun deposit(amount: BigDecimal, symbol: Symbol): DepositId? {
        val blockchainClient = blockchainClient(symbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        val bigIntAmount = amount.toFundamentalUnits(decimals(symbol))
        val tokenAddress = contractAddress(symbol)
        return if (tokenAddress != null) {
            runBlocking {
                val allowanceTxReceipt = blockchainClient
                    .loadERC20(tokenAddress)
                    .approve(exchangeContractAddress.value, bigIntAmount)
                    .sendAsync()
                    .await()
                if (allowanceTxReceipt.isStatusOK) {
                    val txHash = blockchainClient.sendTransaction(
                        exchangeContractAddress,
                        blockchainClient
                            .loadExchangeContract(exchangeContractAddress)
                            .deposit(tokenAddress.value, bigIntAmount).encodeFunctionCall(),
                        BigInteger.ZERO,
                    )
                    apiClient.createDeposit(CreateDepositApiRequest(symbol, bigIntAmount, txHash)).deposit.id
                } else {
                    null
                }
            }
        } else {
            val txHash = blockchainClient.asyncDepositNative(exchangeContractAddress, bigIntAmount)
            apiClient.createDeposit(CreateDepositApiRequest(symbol, bigIntAmount, txHash)).deposit.id
        }
    }

    fun listDeposits(): List<Deposit> =
        apiClient
            .tryListDeposits()
            .fold({ emptyList() }, { it.deposits })

    fun withdraw(amount: BigDecimal, symbol: Symbol): Either<ApiCallFailure, WithdrawalApiResponse> {
        val blockchainClient = blockchainClient(symbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        val bigIntAmount = amount.toFundamentalUnits(decimals(symbol))
        val nonce = System.currentTimeMillis()
        val tx = EIP712Transaction.WithdrawTx(
            walletAddress,
            TokenAddressAndChain(contractAddress(symbol) ?: Address.zero, chain(symbol)),
            bigIntAmount,
            nonce,
            bigIntAmount == BigInteger.ZERO,
            EvmSignature.emptySignature(),
        )
        return apiClient.tryCreateWithdrawal(
            CreateWithdrawalApiRequest(
                symbol,
                bigIntAmount,
                nonce,
                blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)),
            ),
        )
    }

    fun estimateSwap(from: Symbol, to: Symbol, amount: BigDecimal): SwapEstimation {
        val market = config.markets.find { it.baseSymbol == from && it.quoteSymbol == to || it.baseSymbol == to && it.quoteSymbol == from }!!

        return if (market.baseSymbol == from) {
            val price = getMarketPrice(market.id)

            SwapEstimation(
                from = market.baseSymbol,
                fromAmount = amount,
                to = market.quoteSymbol,
                toAmount = price * amount.setScale(market.quoteDecimals),
                price = price,
                market = market,
            )
        } else {
            val price = (BigDecimal(1).setScale(market.quoteDecimals) / getMarketPrice(market.id))
                .setScale(market.baseDecimals, RoundingMode.HALF_EVEN)

            SwapEstimation(
                from = market.quoteSymbol,
                fromAmount = amount,
                to = market.baseSymbol,
                toAmount = price * amount.setScale(market.baseDecimals),
                price = price,
                market = market,
            )
        }
    }

    fun submitSwap(estimation: SwapEstimation): Either<ApiCallFailure, CreateOrderApiResponse> {
        val market = estimation.market
        val (amount, orderSide) = if (estimation.from == market.baseSymbol) {
            Pair(estimation.fromAmount, OrderSide.Sell)
        } else {
            Pair(estimation.toAmount, OrderSide.Buy)
        }

        return apiClient.tryCreateOrder(
            signOrder(
                CreateOrderApiRequest.Market(
                    nonce = generateOrderNonce(),
                    marketId = market.id,
                    side = orderSide,
                    amount = OrderAmount.Fixed(amount.toFundamentalUnits(decimals(market.baseSymbol))),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        )
    }

    fun symbols(): List<Symbol> =
        symbolInfoBySymbol.keys
            .sortedBy { it.value }

    fun airDroppableSymbols(): List<Symbol> =
        symbols().filter { airDropSupported(it) }

    private fun airDropSupported(symbol: Symbol): Boolean {
        if (!faucetSupported) return false

        val symbolInfo = symbolInfoBySymbol[symbol]
        return if (symbolInfo == null) {
            false
        } else {
            symbolInfo.contractAddress == null
        }
    }

    fun airDrop(symbol: Symbol): Either<ApiCallFailure, FaucetApiResponse> =
        apiClient.tryFaucet(FaucetApiRequest(chain(symbol), walletAddress))

    fun getTxReceipt(chainId: ChainId, txHash: TxHash): TransactionReceipt? =
        blockchainClientsByChainId.getValue(chainId).getTransactionReceipt(txHash)

    private fun signOrder(order: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val market = config.markets.first { it.id == order.marketId }
        val blockchainClient = blockchainClient(market.baseSymbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)

        val tx = EIP712Transaction.Order(
            walletAddress,
            baseToken = contractAddress(market.baseSymbol) ?: Address.zero,
            quoteToken = contractAddress(market.quoteSymbol) ?: Address.zero,
            amount = if (order.side == OrderSide.Buy) order.amount else order.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, order.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )

        return order.copy(
            signature = blockchainClient.signData(
                EIP712Helper.computeHash(
                    tx,
                    blockchainClient.chainId,
                    exchangeContractAddress,
                ),
            ),
            verifyingChainId = blockchainClient.chainId,
        )
    }

    fun decimals(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).decimals.toInt()

    private fun contractAddress(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).contractAddress

    private fun chain(symbol: Symbol) = chainBySymbol.getValue(symbol)

    private fun blockchainClient(symbol: Symbol) = blockchainClientsByChainId.getValue(chain(symbol))
}
