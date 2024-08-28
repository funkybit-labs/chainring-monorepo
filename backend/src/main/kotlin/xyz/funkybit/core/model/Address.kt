package xyz.funkybit.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Bech32
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptOpCodes
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification.opPushData
import xyz.funkybit.core.utils.bitcoin.BitcoinSignatureVerification.padZeroHexN
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
            else -> BitcoinAddress.canonicalize(value)
        }
    }
    abstract fun canonicalize(): Address
}

object BitcoinAddressSerializer : KSerializer<BitcoinAddress> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BitcoinAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BitcoinAddress) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = BitcoinAddress.canonicalize(decoder.decodeString())
}

@Serializable(with = BitcoinAddressSerializer::class)
sealed class BitcoinAddress(val value: String) : Address() {
    companion object {
        fun canonicalize(value: String) = Unrecognized(value).canonicalize()
        fun fromKey(params: NetworkParameters, key: ECKey): SegWit {
            val value = org.bitcoinj.core.Address.fromKey(params, key, Script.ScriptType.P2WPKH).toString()
            return if (value.startsWith("bc1q")) {
                SegWit(value, false)
            } else if (value.startsWith("tb1q") || value.startsWith("bcrt1q")) {
                SegWit(value, true)
            } else {
                throw Exception("Not a segwit address")
            }
        }
    }
    override fun canonicalize() = when {
        this.value.startsWith("bc1q") -> SegWit(this.value, false)
        this.value.startsWith("tb1q") -> SegWit(this.value, true)
        this.value.startsWith("bcrt1q") -> SegWit(this.value, true)
        this.value.startsWith("bc1p") -> Taproot(this.value, false)
        this.value.startsWith("tb1p") -> Taproot(this.value, true)
        this.value.startsWith("3") -> P2SH(this.value, false)
        this.value.startsWith("2") -> P2SH(this.value, true)
        this.value.startsWith("1") -> P2PKH(this.value, false)
        this.value.startsWith("m") -> P2PKH(this.value, true)
        else -> Unrecognized(this.value)
    }
    override fun toString() = this.value
    abstract fun script(): String
    data class SegWit(val raw: String, val testnet: Boolean) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Bech32.decode(raw)
            val hash = Utils.HEX.encode(convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false))
            return padZeroHexN(ScriptOpCodes.OP_0.toString(16), 2) + opPushData(hash)
        }

        companion object {
            fun generate(networkParameters: NetworkParameters): SegWit =
                fromKey(networkParameters, ECKey())
        }
    }

    data class Taproot(val raw: String, val testnet: Boolean) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Bech32.decode(raw)
            val tapTweakedPubkey = Utils.HEX.encode(convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false))
            return padZeroHexN(ScriptOpCodes.OP_1.toString(16), 2) + opPushData(tapTweakedPubkey)
        }
    }

    data class P2SH(val raw: String, val testnet: Boolean) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Base58.decode(raw.substring(1))
            val hash = Utils.HEX.encode(decoded)
            return padZeroHexN(ScriptOpCodes.OP_HASH160.toString(16), 2) + opPushData(hash) + padZeroHexN(ScriptOpCodes.OP_EQUAL.toString(16), 2)
        }
    }

    data class P2PKH(val raw: String, val testnet: Boolean) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Base58.decode(raw)
            val hash = Utils.HEX.encode(decoded.copyOfRange(1, 21))
            return padZeroHexN(ScriptOpCodes.OP_DUP.toString(16), 2) +
                padZeroHexN(ScriptOpCodes.OP_HASH160.toString(16), 2) +
                opPushData(hash) +
                padZeroHexN(ScriptOpCodes.OP_EQUALVERIFY.toString(16), 2) +
                padZeroHexN(ScriptOpCodes.OP_CHECKSIG.toString(16), 2)
        }
    }

    data class Unrecognized(val raw: String) : BitcoinAddress(raw) {
        override fun script() = ""
    }

    protected fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()

        for (value in data) {
            acc = (acc shl fromBits) or (value.toInt() and 0xff)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Invalid bit conversion")
        }

        return result.toByteArray()
    }

    fun toBitcoinCoreAddress(params: NetworkParameters): org.bitcoinj.core.Address =
        org.bitcoinj.core.Address.fromString(params, value)
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
