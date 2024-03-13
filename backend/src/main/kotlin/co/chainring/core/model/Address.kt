package co.chainring.core.model

import co.chainring.core.utils.generateHexString
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Address(val value: String) {
    init {
        require(
            value.startsWith("0x") &&
                value.length == 42 &&
                value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
        ) {
            "Invalid address format or not a hex string"
        }
    }

    companion object {
        fun generate() = Address("0x${generateHexString(40)}")
    }
}
