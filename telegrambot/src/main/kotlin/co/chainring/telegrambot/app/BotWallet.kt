package co.chainring.telegrambot.app

import arrow.core.Either
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
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.client.rest.ApiCallFailure
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.client.ws.nonBlocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.client.ws.subscribeToOrders
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.client.ws.subscribeToTrades
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
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TelegramBotUserWalletId
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import com.github.ehsannarmani.bot.Bot
import com.github.ehsannarmani.bot.model.message.TextMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.http4k.client.WebsocketClient
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsStatus
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class WalletAvailableBalances(
    val baseSymbol: Symbol,
    val quoteSymbol: Symbol,
    val availableBaseBalance: String,
    val availableQuoteBalance: String,
)

class TelegramUserWallet(val walletId: TelegramBotUserWalletId, val botSession: BotSession, ecKeyPair: ECKeyPair, val bot: Bot) {

    private val logger = KotlinLogging.logger { }
    private val apiClient = ApiClient(ecKeyPair)
    val config = apiClient.tryGetConfiguration().getOrNull()
        ?: throw Exception("Unable to retrieve config")

    private val chainBySymbol = config.chains.map { chain ->
        chain.symbols.associate { Symbol(it.name) to chain.id }
    }.flatMap { map -> map.entries }.associate(Map.Entry<Symbol, ChainId>::toPair)
    private val symbolInfoBySymbol = config.chains.map { chain ->
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
    var currentMarket = config.markets.find { it.id == "BTC/ETH" } ?: config.markets.first()
    val walletAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(ecKeyPair)))
    private val balances = mutableMapOf<Symbol, BigInteger>()
    private val pendingDeposits = mutableSetOf<TxHash>()

    private var websocket: Websocket? = null

    fun start() {
        WebsocketClient.nonBlocking(apiClient.authToken) { ws ->
            logger.debug { "websocket connected" }
            websocket = ws
            config.markets.map { MarketId(it.id) }.forEach {
                ws.subscribeToPrices(it)
            }
            ws.subscribeToOrders()
            ws.subscribeToTrades()
            ws.subscribeToBalances()
            ws.onMessage {
                val message = Json.decodeFromString<OutgoingWSMessage>(it.bodyString()) as OutgoingWSMessage.Publish
                when (message.topic) {
                    SubscriptionTopic.Trades -> {
                        when (val data = message.data) {
                            is TradeUpdated -> {
                                val trade = data.trade
                                if (trade.settlementStatus == SettlementStatus.Completed) {
                                    sendMessage(formatTrade(trade))
                                    runBlocking {
                                        bot.mainMenu(botSession)
                                    }
                                }
                            }

                            else -> {}
                        }
                    }

                    SubscriptionTopic.Balances -> {
                        val balanceData = message.data as Balances
                        balanceData.balances.forEach {
                            balances[it.symbol] = it.available
                        }
                        if (pendingDeposits.isNotEmpty()) {
                            apiClient.tryListDeposits().getOrNull()?.deposits?.let { deposits ->
                                pendingDeposits.forEach { txHash ->
                                    deposits.firstOrNull { it.txHash == txHash }?.let {
                                        when (it.status) {
                                            Deposit.Status.Failed -> "❌ Deposit of ${it.symbol.value} failed"
                                            Deposit.Status.Complete -> "✅ Deposit of ${it.symbol.value} completed"
                                            else -> null
                                        }?.let { message ->
                                            sendMessage(message)
                                            pendingDeposits.remove(it.txHash)
                                            runBlocking { bot.mainMenu(botSession) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SubscriptionTopic.Orders -> {
                        val (orders, isCreated) = when (val data = message.data) {
                            is OrderCreated -> Pair(listOf(data.order), true)
                            is OrderUpdated -> Pair(listOf(data.order), false)
                            else -> Pair(emptyList(), false)
                        }
                        orders.forEach { order ->
                            sendMessage(formatOrder(order, isCreated))
                        }
                    }

                    is SubscriptionTopic.Prices -> {
                        val prices = message.data as Prices
                        prices.ohlc.lastOrNull()?.let {
                            marketPrices[prices.market] = it.close.toBigDecimal()
                        }
                    }

                    else -> {}
                }
            }
            ws.onClose {
                logger.debug { "web socket closed" }
            }
            ws.onError {
                logger.error(it) { "web socket exception" }
            }
        }
    }

    private fun sendMessage(message: String) {
        runBlocking {
            bot.sendMessage(
                TextMessage(
                    text = message,
                    chatId = botSession.id,
                ),
            )
        }
    }

    fun switchCurrentMarket(marketId: MarketId) {
        config.markets.find { it.id == marketId.value }?.let {
            currentMarket = it
        }
    }

    fun stop() {
        try {
            websocket?.let { ws ->
                config.markets.map { MarketId(it.id) }.forEach {
                    ws.unsubscribe(SubscriptionTopic.Prices(it, OHLCDuration.P5M))
                }
                ws.unsubscribe(SubscriptionTopic.Trades)
                ws.unsubscribe(SubscriptionTopic.Orders)
                ws.unsubscribe(SubscriptionTopic.Balances)
                ws.close(WsStatus.GOING_AWAY)
            }
        } catch (e: Exception) {
            logger.error(e) { "failed to stop bot wallet" }
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
            formatAmount(baseSymbol, balances[baseSymbol] ?: BigInteger.ZERO),
            formatAmount(quoteSymbol, balances[quoteSymbol] ?: BigInteger.ZERO),
        )
    }

    private fun formatAmountWithSymbol(symbol: Symbol, amount: BigInteger): String {
        return "${formatAmount(symbol, amount)} ${symbol.value}"
    }

    private fun getWalletBalance(symbol: Symbol): BigInteger {
        val blockchainClient = blockchainClient(symbol)
        return contractAddress(symbol)?.let {
            blockchainClient.getERC20Balance(it, walletAddress)
        } ?: blockchainClient.getNativeBalance(walletAddress)
    }

    fun getMarketPrice(): String {
        return marketPrices[MarketId(currentMarket.id)]?.toPlainString() ?: "Unknown"
    }

    fun getFormattedWalletBalance(symbol: Symbol): String {
        return formatAmount(symbol, getWalletBalance(symbol))
    }

    fun depositBase(amount: String) {
        deposit(amount, currentMarket.baseSymbol)
    }

    fun depositQuote(amount: String) {
        deposit(amount, currentMarket.quoteSymbol)
    }

    private fun deposit(amount: String, symbol: Symbol) {
        val blockchainClient = blockchainClient(symbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        val bigIntAmount = amountToBigInteger(symbol, amount)
        val tokenAddress = contractAddress(symbol)
        if (tokenAddress != null) {
            val erc20Contract = blockchainClient.loadERC20(tokenAddress)
            erc20Contract.approve(exchangeContractAddress.value, bigIntAmount).sendAsync().thenAccept { txReceipt ->
                if (txReceipt.isStatusOK) {
                    val txHash = blockchainClient.sendTransaction(
                        exchangeContractAddress,
                        blockchainClient.loadExchangeContract(exchangeContractAddress).deposit(tokenAddress.value, bigIntAmount).encodeFunctionCall(),
                        BigInteger.ZERO,
                    )
                    pendingDeposits.add(txHash)
                    sendMessage("✅ Deposit of ${symbol.value} initiated")
                } else {
                    sendMessage("❌ Deposit of ${symbol.value} approval failed")
                }
            }
        } else {
            val txHash = blockchainClient.asyncDepositNative(exchangeContractAddress, bigIntAmount)
            pendingDeposits.add(txHash)
            sendMessage("✅ Deposit of ${symbol.value} initiated")
        }
    }

    fun withdrawBase(amount: String): Either<ApiCallFailure, WithdrawalApiResponse> {
        return withdraw(amount, currentMarket.baseSymbol)
    }

    fun withdrawQuote(amount: String): Either<ApiCallFailure, WithdrawalApiResponse> {
        return withdraw(amount, currentMarket.quoteSymbol)
    }

    private fun withdraw(amount: String, symbol: Symbol): Either<ApiCallFailure, WithdrawalApiResponse> {
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
        val blockchainClient = blockchainClient(currentMarket.baseSymbol)
        val exchangeContractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
        return apiClient.tryCreateOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = MarketId(currentMarket.id),
                side = side,
                amount = amountToBigInteger(currentMarket.baseSymbol, amount),
                signature = EvmSignature.emptySignature(),
                verifyingChainId = ChainId.empty,
            ).let {
                val tx = EIP712Transaction.Order(
                    walletAddress,
                    baseToken = contractAddress(currentMarket.baseSymbol) ?: Address.zero,
                    quoteToken = contractAddress(currentMarket.quoteSymbol) ?: Address.zero,
                    amount = if (it.side == OrderSide.Buy) it.amount else it.amount.negate(),
                    price = BigInteger.ZERO,
                    nonce = BigInteger(1, it.nonce.toHexBytes()),
                    EvmSignature.emptySignature(),
                )
                it.copy(signature = blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress)))
            },
        )
    }

    private fun decimals(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).decimals.toInt()

    private fun contractAddress(symbol: Symbol) = symbolInfoBySymbol.getValue(symbol).contractAddress

    private fun chain(symbol: Symbol) = chainBySymbol.getValue(symbol)

    private fun blockchainClient(symbol: Symbol) = blockchainClientsByChainId.getValue(chain(symbol))

    private fun amountToBigInteger(symbol: Symbol, amount: String) = BigDecimal(amount).toFundamentalUnits(decimals(symbol))

    private fun formatAmount(symbol: Symbol, amount: BigInteger): String {
        return amount.fromFundamentalUnits(decimals(symbol)).setScale(minOf(decimals(symbol), 8), RoundingMode.FLOOR).toPlainString()
    }

    private fun formatOrder(order: Order, isCreated: Boolean = false): String {
        val market = config.markets.first { it.id == order.marketId.value }
        val status = when (order.status) {
            OrderStatus.Filled -> "filled"
            OrderStatus.Partial -> "partially filled (${formatAmountWithSymbol(market.baseSymbol, order.executions.first().amount)})"
            OrderStatus.Failed, OrderStatus.Rejected -> "rejected"
            OrderStatus.Cancelled -> "cancelled"
            OrderStatus.Open -> if (isCreated) "opened" else "updated"
            OrderStatus.Expired -> "expired"
        }
        val executionPrice = when (order.status) {
            OrderStatus.Partial, OrderStatus.Filled -> "at price of ${order.executions.first().price.setScale(6, RoundingMode.FLOOR).toPlainString()}"
            else -> ""
        }
        val emoji = when (order.status) {
            OrderStatus.Partial, OrderStatus.Filled, OrderStatus.Open -> "✅"
            else -> "❌"
        }
        return "$emoji Order to ${order.side} ${formatAmountWithSymbol(market.baseSymbol, order.amount)} $status $executionPrice"
    }

    private fun formatTrade(trade: Trade): String {
        val market = config.markets.first { it.id == trade.marketId.value }
        return "✅ Trade to ${trade.side} ${formatAmountWithSymbol(market.baseSymbol, trade.amount)} has settled"
    }
}
