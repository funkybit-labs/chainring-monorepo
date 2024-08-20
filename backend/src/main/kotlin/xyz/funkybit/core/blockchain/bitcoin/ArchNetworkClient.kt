package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.model.rpc.ArchRpcParams
import xyz.funkybit.core.model.rpc.ArchRpcRequest

@OptIn(ExperimentalUnsignedTypes::class)
object ArchNetworkClient : JsonRpcClientBase(
    System.getenv("ARCH_NETWORK_RPC_URL") ?: "http://localhost:9001",
    null,
) {

    override val logger = KotlinLogging.logger {}
    override val mediaType = "application/json".toMediaTypeOrNull()

    fun deployProgram(elf: UByteArray): String {
        return getValue(
            ArchRpcRequest(
                "deploy_program",
                ArchRpcParams(ArchNetworkRpc.DeployProgramParams(elf)),
            ),
        )
    }

    fun getProgram(programId: String): UByteArray {
        return getValue(
            ArchRpcRequest(
                "get_program",
                ArchRpcParams(programId),
            ),
        )
    }

    fun getNetworkAddress(): BitcoinAddress = getContractAddress("")

    fun getContractAddress(contract: String): BitcoinAddress {
        return getValue(
            ArchRpcRequest(
                "get_contract_address",
                ArchRpcParams(ArchNetworkRpc.GetContractAddress(contract.toByteArray().toUByteArray())),
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

    fun readUtxo(utxoId: UtxoId): ArchNetworkRpc.ReadUtxoResult {
        return getValue(
            ArchRpcRequest(
                "read_utxo",
                ArchRpcParams(ArchNetworkRpc.ReadUtxoParams(utxoId.value)),
            ),
        )
    }

    inline fun <reified T> getValue(request: ArchRpcRequest, logResponseBody: Boolean = true): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }
}
