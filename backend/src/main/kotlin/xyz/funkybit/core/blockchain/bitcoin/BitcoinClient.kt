package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcRequest
import xyz.funkybit.core.utils.bitcoin.inSatsAsDecimalString
import java.math.BigInteger

object BitcoinClient : JsonRpcClientBase(
    System.getenv("BITCOIN_NETWORK_RPC_URL") ?: "http://localhost:18443/wallet/testwallet",
    if ((System.getenv("BITCOIN_NETWORK_ENABLE_BASIC_AUTH") ?: "true").toBoolean()) {
        BasicAuthInterceptor(
            System.getenv("BITCOIN_NETWORK_RPC_USER") ?: "user",
            System.getenv("BITCOIN_NETWORK_RPC_PASSWORD") ?: "password",
        )
    } else {
        null
    },
) {

    override val logger = KotlinLogging.logger {}
    private val faucetAddress = BitcoinAddress.canonicalize(System.getenv("BITCOIN_FAUCET_ADDRESS") ?: "bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7")

    fun mine(nBlocks: Int): List<String> {
        return try {
            getValue(
                BitcoinRpcRequest(
                    "generatetoaddress",
                    BitcoinRpcParams(listOf(nBlocks, faucetAddress)),
                ),
                noLog = true,
            )
        } catch (e: Exception) {
            listOf()
        }
    }

    fun sendToAddress(address: BitcoinAddress, amount: BigInteger): TxHash {
        return getValue<TxHash>(
            BitcoinRpcRequest(
                "sendtoaddress",
                BitcoinRpcParams(listOf(address.value, amount.inSatsAsDecimalString())),
            ),
        ).also {
            mine(1)
        }
    }

    inline fun <reified T> getValue(request: BitcoinRpcRequest, logResponseBody: Boolean = true, noLog: Boolean = false): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody, noLog)
        return json.decodeFromJsonElement(jsonElement)
    }
}
