package xyz.funkybit.core.model.bitcoin
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import java.nio.ByteBuffer

sealed class SystemInstruction {

    data class CreateNewAccount(
        val utxo: ArchNetworkRpc.UtxoMeta,
    ) : SystemInstruction()

    data class ExtendBytes(
        val bytes: ByteArray,
    ) : SystemInstruction()

    data object MakeAccountExecutable : SystemInstruction()

    data class ChangeAccountOwnership(
        val pubkey: ArchNetworkRpc.Pubkey,
    ) : SystemInstruction()

    fun serialize(): ByteArray {
        return when (this) {
            is CreateNewAccount -> {
                val buffer = ByteBuffer.allocate(37)
                buffer.put(0)
                buffer.put(this.utxo.serialize())
                buffer.array()
            }
            is ExtendBytes -> {
                val buffer = ByteBuffer.allocate(1 + this.bytes.size)
                buffer.put(1)
                buffer.put(this.bytes)
                buffer.array()
            }
            is MakeAccountExecutable -> {
                val buffer = ByteBuffer.allocate(1)
                buffer.put(2)
                buffer.array()
            }
            is ChangeAccountOwnership -> {
                val buffer = ByteBuffer.allocate(33)
                buffer.put(3)
                buffer.put(this.pubkey.serialize())
                buffer.array()
            }
        }
    }
}
