package co.chainring.core.model

import co.chainring.core.utils.generateHexString
import kotlinx.serialization.Serializable
import org.web3j.crypto.Keys

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
    }
}

fun Address.toChecksumAddress(): Address {
    return Address(Keys.toChecksumAddress(this.value))
}

fun Address.abbreviated(): String {
    return this.value.take(6) + "..." + this.value.takeLast(4)
}
