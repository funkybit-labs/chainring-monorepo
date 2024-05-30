package co.chainring.telegrambot.app

import arrow.core.Either
import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeUpdated
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
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TelegramBotUserWalletEntity
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

data class WalletAvailableBalances(
    val baseSymbol: Symbol,
    val quoteSymbol: Symbol,
    val availableBaseBalance: BigInteger,
    val availableQuoteBalance: BigInteger,
)

class BotSessionCurrentWallet(
    val wallet: TelegramBotUserWalletEntity,
    val onBalanceUpdated: (List<Balance>) -> Unit,
    val onOrderCreated: (Order) -> Unit,
    val onOrderUpdated: (Order) -> Unit,
    val onTradeUpdated: (Trade) -> Unit,
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
    var currentMarket = config.markets.find { it.id.value == "BTC/ETH" } ?: config.markets.first()
    val walletAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(ecKeyPair)))
    private val balances = mutableMapOf<Symbol, BigInteger>()

    private var websocket: WebSocket? = null
    private val websocketSubscriptionTopics = listOf(
        SubscriptionTopic.Orders,
        SubscriptionTopic.Trades,
        SubscriptionTopic.Balances,
    ) + config.markets.map { market -> SubscriptionTopic.Prices(market.id, OHLCDuration.P5M) }

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
            is Balances -> {
                message.balances.forEach {
                    balances[it.symbol] = it.available
                }
                onBalanceUpdated(message.balances)
            }
            is Prices -> {
                message.ohlc.lastOrNull()?.let {
                    marketPrices[message.market] = it.close.toBigDecimal()
                }
            }
            is OrderCreated -> {
                onOrderCreated(message.order)
            }
            is OrderUpdated -> {
                onOrderUpdated(message.order)
            }
            is TradeUpdated -> {
                onTradeUpdated(message.trade)
            }
            else -> {}
        }
    }

    fun switchCurrentMarket(marketId: MarketId) {
        config.markets.find { it.id == marketId }?.let {
            currentMarket = it
        }
    }

    fun getBalances(): WalletAvailableBalances {
        val baseSymbol = currentMarket.baseSymbol
        val quoteSymbol = currentMarket.quoteSymbol

        if (balances.isEmpty()) {
            apiClient.getBalances().also { response ->
                response.balances.forEach { balances[it.symbol] = it.available }
            }
        }

        return WalletAvailableBalances(
            baseSymbol,
            quoteSymbol,
            balances[baseSymbol] ?: BigInteger.ZERO,
            balances[quoteSymbol] ?: BigInteger.ZERO,
        )
    }

    fun getWalletBalance(symbol: Symbol): BigInteger {
        val blockchainClient = blockchainClient(symbol)
        return contractAddress(symbol)?.let {
            blockchainClient.getERC20Balance(it, walletAddress)
        } ?: blockchainClient.getNativeBalance(walletAddress)
    }

    fun getMarketPrice(): BigDecimal? {
        return marketPrices[currentMarket.id]
    }

    suspend fun deposit(amount: String, symbol: Symbol): TxHash? {
        val blockchainClient = blockchainClient(symbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        val bigIntAmount = amountToBigInteger(symbol, amount)
        val tokenAddress = contractAddress(symbol)
        return if (tokenAddress != null) {
            val allowanceTxReceipt = blockchainClient
                .loadERC20(tokenAddress)
                .approve(exchangeContractAddress.value, bigIntAmount)
                .sendAsync()
                .await()
            if (allowanceTxReceipt.isStatusOK) {
                blockchainClient.sendTransaction(
                    exchangeContractAddress,
                    blockchainClient
                        .loadExchangeContract(exchangeContractAddress)
                        .deposit(tokenAddress.value, bigIntAmount).encodeFunctionCall(),
                    BigInteger.ZERO,
                )
            } else {
                null
            }
        } else {
            blockchainClient.asyncDepositNative(exchangeContractAddress, bigIntAmount)
        }
    }

    fun listDeposits(): List<Deposit> =
        apiClient
            .tryListDeposits()
            .fold({ emptyList() }, { it.deposits })

    fun withdraw(amount: String, symbol: Symbol): Either<ApiCallFailure, WithdrawalApiResponse> {
        val blockchainClient = blockchainClient(symbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        val bigIntAmount = amountToBigInteger(symbol, amount)
        val nonce = System.currentTimeMillis()
        val tx = EIP712Transaction.WithdrawTx(
            walletAddress,
            TokenAddressAndChain(contractAddress(symbol) ?: Address.zero, chain(symbol)),
            bigIntAmount,
            nonce,
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

    fun createOrder(amount: String, side: OrderSide): Either<ApiCallFailure, CreateOrderApiResponse> {
        return apiClient.tryCreateOrder(
            signOrder(
                CreateOrderApiRequest.Market(
                    nonce = generateOrderNonce(),
                    marketId = currentMarket.id,
                    side = side,
                    amount = amountToBigInteger(currentMarket.baseSymbol, amount),
                    signature = EvmSignature.emptySignature(),
                    verifyingChainId = ChainId.empty,
                ),
            ),
        )
    }

    private fun signOrder(order: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val blockchainClient = blockchainClient(currentMarket.baseSymbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)

        val tx = EIP712Transaction.Order(
            walletAddress,
            baseToken = contractAddress(currentMarket.baseSymbol) ?: Address.zero,
            quoteToken = contractAddress(currentMarket.quoteSymbol) ?: Address.zero,
            amount = if (order.side == OrderSide.Buy) order.amount else order.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, order.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
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

    private fun decimals(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).decimals.toInt()

    private fun contractAddress(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).contractAddress

    private fun chain(symbol: Symbol) = chainBySymbol.getValue(symbol)

    private fun blockchainClient(symbol: Symbol) = blockchainClientsByChainId.getValue(chain(symbol))

    private fun amountToBigInteger(symbol: Symbol, amount: String) = BigDecimal(amount).toFundamentalUnits(decimals(symbol))
}
