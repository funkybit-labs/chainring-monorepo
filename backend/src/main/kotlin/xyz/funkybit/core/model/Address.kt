package xyz.funkybit.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import xyz.funkybit.core.utils.generateHexString

object AddressSerializer : KSerializer<Address> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Address", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Address) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = Address.auto(decoder.decodeString())
}

@Serializable(with = AddressSerializer::class)
sealed class Address {
    companion object {
        fun auto(value: String): Address = when {
            value.startsWith("0x") -> EvmAddress.canonicalize(value)
            else -> BitcoinAddress(value)
        }
    }
    abstract fun canonicalize(): Address
}

@Serializable
data class BitcoinAddress(val value: String) : Address() {
    override fun canonicalize() = BitcoinAddress(this.value)
    override fun toString() = this.value
}

@Serializable
data class EvmAddress(val value: String) : Address() {
    override fun canonicalize() = Companion.canonicalize(this.value)
    override fun toString() = this.value
    init {
        require(Keys.toChecksumAddress(value) == value) {
            "Invalid address format or not a checksum address"
        }
    }
    companion object {
        fun canonicalize(value: String) = EvmAddress(Keys.toChecksumAddress(value))

        fun generate() = canonicalize("0x${generateHexString(40)}")

        val zero = EvmAddress("0x0000000000000000000000000000000000000000")

        fun fromPrivateKey(privateKey: String): EvmAddress =
            canonicalize(Credentials.create(privateKey).address)
    }
}

fun EvmAddress.toChecksumAddress(): EvmAddress {
    return EvmAddress.canonicalize(this.value)
}

fun EvmAddress.abbreviated(): String {
    return this.value.take(6) + "..." + this.value.takeLast(4)
}
