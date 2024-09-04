package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
@JvmInline
value class BitcoinSignature(val value: String) {

    companion object {
        @OptIn(ExperimentalEncodingApi::class, ExperimentalStdlibApi::class)
        fun emptySignature(): BitcoinSignature {
            return BitcoinSignature(
                value = Base64.encode("0".repeat(130).hexToByteArray()),
            )
        }
    }
}

fun String.toBitcoinSignature(): BitcoinSignature {
    return BitcoinSignature(this)
}
