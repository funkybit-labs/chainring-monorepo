package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.UtxoId
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

    fun sendTransaction(transaction: ArchNetworkRpc.RuntimeTransaction): String {
        return getValue(
            ArchRpcRequest(
                "send_transaction",
                ArchRpcParams(transaction),
            ),
        )
    }

    fun getProcessedTransaction(txId: String): ArchNetworkRpc.ProcessedTransaction {
        return getValue(
            ArchRpcRequest(
                "get_processed_transaction",
                ArchRpcParams(txId),
            ),
        )
    }

    fun assignAuthority(request: ArchNetworkRpc.AssignAuthorityParams): ArchNetworkRpc.ProcessedTransaction {
        return getValue(
            ArchRpcRequest(
                "assign_authority",
                ArchRpcParams(request),
            ),
        )
    }

    fun readUtxo(utxoId: UtxoId): ArchNetworkRpc.ReadUtxoResult {
        return getValue(
            ArchRpcRequest(
                "read_utxo",
                ArchRpcParams(ArchNetworkRpc.ReadUtxoParams(utxoId.value)),
            ),
        )
    }

    fun getBestBlockHash(): String {
        return getValue(
            ArchRpcRequest(
                "get_best_block_hash",
            ),
        )
    }

    fun getBlock(blockHash: String): String {
        return getValue(
            ArchRpcRequest(
                "get_block",
                ArchRpcParams(blockHash),
            ),
        )
    }

    inline fun <reified T> getValue(request: ArchRpcRequest, logResponseBody: Boolean = true): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }
}
