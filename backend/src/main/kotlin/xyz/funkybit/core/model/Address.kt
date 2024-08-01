package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import xyz.funkybit.core.utils.generateHexString

@Serializable
@JvmInline
value class Address(val value: String) {
    init {
        require(Keys.toChecksumAddress(value) == value) {
            "Invalid address format or not a checksum address"
        }
    }
    companion object {
        fun generate() = Address(Keys.toChecksumAddress("0x${generateHexString(40)}"))

        val zero = Address("0x0000000000000000000000000000000000000000")

        fun fromPrivateKey(privateKey: String): Address =
            Address(Keys.toChecksumAddress(Credentials.create(privateKey).address))
    }
}

fun Address.toChecksumAddress(): Address {
    return Address(Keys.toChecksumAddress(this.value))
}

fun Address.abbreviated(): String {
    return this.value.take(6) + "..." + this.value.takeLast(4)
}
