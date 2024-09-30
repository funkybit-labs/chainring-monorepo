package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.model.rpc.ArchRpcParams
import xyz.funkybit.core.model.rpc.ArchRpcRequest

object ArchNetworkClient : JsonRpcClientBase(
    System.getenv("ARCH_NETWORK_RPC_URL") ?: "http://localhost:9002",
    null,
) {

    const val MAX_TX_SIZE = 1024
    const val MAX_INSTRUCTION_SIZE = MAX_TX_SIZE - 103 // version (4), 1 signer (33), 1 signature (65), 1 byte for instruction count

    override val logger = KotlinLogging.logger {}
    override val mediaType = "application/json".toMediaTypeOrNull()

    fun getAccountAddress(pubKey: ArchNetworkRpc.Pubkey): BitcoinAddress {
        return getValue(
            ArchRpcRequest(
                "get_account_address",
                ArchRpcParams(pubKey),
            ),
        )
    }

    fun sendTransaction(transaction: ArchNetworkRpc.RuntimeTransaction): TxHash {
        return getValue(
            ArchRpcRequest(
                "send_transaction",
                ArchRpcParams(transaction),
            ),
        )
    }

    fun sendTransactions(transactions: List<ArchNetworkRpc.RuntimeTransaction>): List<TxHash> {
        return getValue(
            ArchRpcRequest(
                "send_transactions",
                ArchRpcParams(transactions),
            ),
        )
    }

    fun getProcessedTransaction(txId: TxHash): ArchNetworkRpc.ProcessedTransaction? {
        return try {
            getValue(
                ArchRpcRequest(
                    "get_processed_transaction",
                    ArchRpcParams(txId.value),
                ),
            )
        } catch (e: JsonRpcException) {
            if (e.error.code != 404) {
                throw e
            }
            null
        }
    }

    fun readAccountInfo(pubKey: ArchNetworkRpc.Pubkey): ArchNetworkRpc.AccountInfoResult {
        return getValue(
            ArchRpcRequest(
                "read_account_info",
                ArchRpcParams(pubKey),
            ),
        )
    }

    inline fun <reified T> getValue(request: ArchRpcRequest, logResponseBody: Boolean = true): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }
}
