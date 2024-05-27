package co.chainring.mocker.core

import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.client.ws.subscribe
import co.chainring.core.client.ws.unsubscribe
import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.utils.Faucet
import co.chainring.core.utils.TraceRecorder
import co.chainring.integrationtests.utils.Wallet
import org.web3j.crypto.Keys
import java.math.BigInteger
import org.web3j.crypto.ECKeyPair
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.time.Duration.Companion.seconds

abstract class Actor(
    private val marketIds: List<MarketId>,
    private val native: BigInteger?,
    private val assets: Map<String, BigInteger>,
    keyPair: ECKeyPair
) {
    abstract val id: String
    protected abstract val logger: KLogger
    protected val apiClient = ApiClient(keyPair, traceRecorder = TraceRecorder.full)
    protected val wallet = Wallet(apiClient)
    protected var balances = mutableMapOf<String, BigInteger>()
    protected var pendingTrades = mutableListOf<Trade>()
    protected var settledTrades = mutableListOf<Trade>()

    protected var websocket: WebSocket? = null
    protected abstract val websocketSubscriptionTopics: List<SubscriptionTopic>
    protected var wsCooldownBeforeReconnecting = 5.seconds

    protected var markets = setOf<Market>()

    open fun start() {
        logger.info { "$id: starting" }

        markets = apiClient.getConfiguration().markets.filter { marketIds.contains(it.id) }.toSet()
        depositAssets()
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

    protected fun depositAssets() {
        // quick fix for org.web3j.protocol.exceptions.JsonRpcError: replacement transaction underpriced
        // looks like same nonce is re-used due to concurrent start of actors which lead to exiting thread
        var deposited = false
        logger.debug { "$id: going to deposit" }
        while (!deposited) {
            try {
                synchronized(Actor::class) {
                    Faucet.fund(wallet.address, (native ?: 2.toFundamentalUnits(18)) * BigInteger.TWO)
                    native?.let { wallet.depositNative(it) }
                    assets.forEach { (symbol, amount) ->
                        wallet.mintERC20(symbol, amount)
                        wallet.depositERC20(symbol, amount)
                    }
                    val fundedAssets = mutableSetOf<String>()
                    while (fundedAssets.size < assets.size + if (native == null) 0 else 1) {
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
