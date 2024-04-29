package co.chainring.core

import co.chainring.apps.api.model.Trade
import co.chainring.integrationtests.utils.ApiClient
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TraceRecorder
import co.chainring.integrationtests.utils.Wallet
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.util.UUID

open class Actor(val native: BigInteger?, val assets: Map<String, BigInteger>) {
    protected val id = UUID.randomUUID().toString()
    protected val keyPair = Keys.createEcKeyPair()
    protected val apiClient = ApiClient(keyPair, traceRecorder = TraceRecorder.full)
    protected val wallet = Wallet(apiClient)
    protected var balances = mutableMapOf<String, BigInteger>()
    protected var pendingTrades = mutableListOf<Trade>()
    protected var settledTrades = mutableListOf<Trade>()

    protected fun depositAssets() {
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
}