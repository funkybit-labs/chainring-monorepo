package xyz.funkybit.mocker.core

import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.Trade
import xyz.funkybit.apps.api.model.websocket.OutgoingWSMessage
import xyz.funkybit.apps.api.model.websocket.Publishable
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.integrationtests.utils.ApiClient
import xyz.funkybit.integrationtests.utils.subscribe
import xyz.funkybit.integrationtests.utils.unsubscribe
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.core.utils.TraceRecorder
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.Wallet
import java.math.BigInteger
import org.web3j.crypto.ECKeyPair
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

abstract class Actor(
    private val marketIds: List<MarketId>,
    private val nativeAssets: Map<String, BigInteger>,
    private val assets: Map<String, BigInteger>,
    keyPair: WalletKeyPair
) {
    abstract val id: String
    protected abstract val logger: KLogger
    protected val apiClient = ApiClient(keyPair, traceRecorder = TraceRecorder.full)
    protected val wallet = Wallet(apiClient)
    protected var balances = mutableMapOf<String, BigInteger>()
    protected var pendingTrades = mutableListOf<Trade>()

    protected var websocket: WebSocket? = null
    protected abstract val websocketSubscriptionTopics: List<SubscriptionTopic>
    protected var wsCooldownBeforeReconnecting = 5.seconds

    protected var markets = setOf<Market>()
    private lateinit var chainIdBySymbol: Map<String, ChainId>
    private val faucetPossible = (System.getenv("FAUCET_POSSIBLE") ?: "0") == "1"
    private val noFaucetNativeDepositRatio = (System.getenv("NO_FAUCET_NATIVE_DEPOSIT_RATIO") ?: "0.5").toBigDecimal()

    open fun start() {
        logger.info { "$id: starting" }

        val config = apiClient.getConfiguration()
        chainIdBySymbol = config.chains.map { chain -> chain.symbols.map { it.name to chain.id } }.flatten().toMap()
        markets = config.markets.filter { marketIds.contains(it.id) }.toSet()
        if (faucetPossible) {
            depositAssets()
        } else {
            depositNoFaucet()
        }
        connectWebsocket()

        onStarted()

        logger.info { "$id: started" }
    }

    protected open fun onStarted() {}

    fun stop() {
        logger.info { "$id: stopping" }

        websocket?.also { ws ->
            websocketSubscriptionTopics.forEach(ws::unsubscribe)
            ws.close(1001, "Going away")
        }

        onStopping()

        logger.info { "$id: stopped" }
    }

    protected open fun onStopping() {}

    protected fun connectWebsocket() {
        logger.debug { "$id: connecting websocket" }
        websocket = apiClient.newWebSocket(object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                websocketSubscriptionTopics.forEach(webSocket::subscribe)
                onWebsocketConnected(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val decodedMessage = Json.decodeFromString<OutgoingWSMessage>(text) as OutgoingWSMessage.Publish
                    handleWebsocketMessage(decodedMessage.data)
                } catch (e: Exception) {
                    logger.warn(e) { "$id: error while handling websocket message" }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectWebsocket()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.warn(t) { "$id: websocket error, reconnecting in $wsCooldownBeforeReconnecting" }
                webSocket.cancel()
                Thread.sleep(wsCooldownBeforeReconnecting.inWholeMilliseconds)
                connectWebsocket()
            }
        })
    }

    protected open fun onWebsocketConnected(webSocket: WebSocket) {}
    protected abstract fun handleWebsocketMessage(message: Publishable)

    private fun depositNoFaucet() {
        val config = apiClient.getConfiguration()
        var deposited = false
        logger.debug { "$id: going to deposit (no faucet)" }
        while (!deposited) {
            try {
                val initialBalances = apiClient.getBalances().balances
                synchronized(Actor::class) {
                    val deposits = config.chains.flatMap { chain ->
                        wallet.switchChain(chain.id)
                        chain.symbols.mapNotNull { symbol ->
                            if (markets.find { it.baseSymbol.value == symbol.name || it.quoteSymbol.value == symbol.name} != null) {
                                val balanceToDeposit = if (symbol.contractAddress == null) {
                                    wallet.getWalletBalance(symbol).let { assetAmount ->
                                        AssetAmount(
                                            assetAmount.symbol,
                                            assetAmount.amount.multiply(noFaucetNativeDepositRatio)
                                        )
                                    }
                                } else {
                                    wallet.getWalletBalance(symbol)
                                }
                                if (balanceToDeposit.inFundamentalUnits > BigInteger.ZERO) {
                                    val receipt = wallet.depositAndWaitForTxReceipt(balanceToDeposit)
                                    apiClient.createDeposit(
                                        CreateDepositApiRequest(
                                            symbol = Symbol(symbol.name),
                                            amount = balanceToDeposit.inFundamentalUnits,
                                            txHash = TxHash(receipt.transactionHash)
                                        )
                                    )
                                } else null
                            } else null
                        }
                    }
                    val fundedAssets = mutableSetOf<String>()
                    while (fundedAssets.size < deposits.size) {
                        Thread.sleep(100)
                        apiClient.getBalances().balances.forEach { balance ->
                            if (balance.available > initialBalances.first { it.symbol == balance.symbol }.available) {
                                fundedAssets.add(balance.symbol.value)
                            }
                        }
                    }
                    apiClient.getBalances().balances.forEach { balance ->
                        balances[balance.symbol.value] = balance.available
                    }
                }
                deposited = true
            } catch (e: Exception) {
                logger.warn(e) { "$id: retrying deposit because of ${e.message}" }
            }
        }
    }

    private fun depositAssets() {
        // quick fix for org.web3j.protocol.exceptions.JsonRpcError: replacement transaction underpriced
        // looks like same nonce is re-used due to concurrent start of actors which lead to exiting thread
        var deposited = false
        logger.debug { "$id: going to deposit" }
        while (!deposited) {
            try {
                val config = apiClient.getConfiguration()
                val initialBalances = apiClient.getBalances().balances
                val gasFeesAmount = BigDecimal("0.1").movePointRight(18).toBigInteger()

                synchronized(Actor::class) {
                    val nativeAmountByChainId = nativeAssets.map { chainIdBySymbol.getValue(it.key) to it.value }.toMap()
                    val chainIds = nativeAmountByChainId.keys + assets.map { chainIdBySymbol.getValue(it.key) }.toSet()
                    chainIds.forEach { chainId ->
                        when (val nativeAmount = nativeAmountByChainId[chainId]) {
                            null -> {
                                // put some balance enough to pay gas, but do not deposit
                                Faucet.fundAndWaitForTxReceipt(wallet.evmAddress, gasFeesAmount, chainId)
                            }
                            else -> {
                                Faucet.fundAndWaitForTxReceipt(wallet.evmAddress, nativeAmount + gasFeesAmount, chainId)

                                val nativeSymbol = config.chains.first { it.id == chainId }.symbols.first { it.contractAddress == null }
                                val availableAmount = initialBalances.find { it.symbol.value == nativeSymbol.name }?.available ?: BigInteger.ZERO
                                val deltaAmount = nativeAmount - availableAmount

                                if (deltaAmount > BigInteger.ZERO) {
                                    logger.debug { "Funding ${wallet.evmAddress} with ${deltaAmount * BigInteger.TWO} on chain $chainId" }
                                    wallet.switchChain(chainId)
                                    logger.debug { "Native deposit $deltaAmount to ${wallet.evmAddress} on chain $chainId" }
                                    val receipt = wallet.depositAndWaitForTxReceipt(AssetAmount(nativeSymbol, deltaAmount))
                                    apiClient.createDeposit(CreateDepositApiRequest(
                                        symbol = Symbol(nativeSymbol.name),
                                        amount = deltaAmount,
                                        txHash = TxHash(receipt.transactionHash)
                                    ))
                                } else {
                                    logger.debug { "Skipping funding for ${wallet.evmAddress}, available native balance $availableAmount" }
                                }
                            }
                        }
                    }
                    assets.forEach { (symbol, desiredAmount) ->
                        val chainId = chainIdBySymbol.getValue(symbol)
                        val symbolInfo = config.chains.first { it.id == chainId }.symbols.first { it.name == symbol }
                        val availableAmount = initialBalances.find { it.symbol.value == symbol }?.available ?: BigInteger.ZERO
                        val deltaAmount = desiredAmount - availableAmount

                        if (deltaAmount > BigInteger.ZERO) {
                            wallet.switchChain(chainId)
                            wallet.mintERC20AndWaitForReceipt(symbol, deltaAmount)
                            logger.debug { "$symbol deposit $deltaAmount to ${wallet.evmAddress} on chain $chainId" }
                            val receipt = wallet.depositAndWaitForTxReceipt(AssetAmount(symbolInfo, deltaAmount))
                            apiClient.createDeposit(CreateDepositApiRequest(
                                symbol = Symbol(symbol),
                                amount = deltaAmount,
                                txHash = TxHash(receipt.transactionHash)
                            ))
                        } else {
                            logger.debug { "Skipping $symbol deposit to ${wallet.evmAddress} on chain $chainId, available balance $availableAmount" }
                        }
                    }
                    val fundedAssets = mutableSetOf<String>()
                    while (fundedAssets.size < assets.size + nativeAssets.size) {
                        Thread.sleep(100)
                        apiClient.getBalances().balances.forEach {
                            if (it.available > BigInteger.ZERO) {
                                fundedAssets.add(it.symbol.value)
                            }
                            balances[it.symbol.value] = it.available
                        }
                    }
                }
                deposited = true
            } catch (e: Exception) {
                logger.warn(e) { "$id: retrying deposit because of ${e.message}" }
            }
        }
        logger.debug { "$id: deposited" }
    }
}
