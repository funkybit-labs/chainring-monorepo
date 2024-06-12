package co.chainring.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Percentage(val value: Int) {
    init {
        require(value in 1..MAX_VALUE) { "Invalid percentage" }
    }

    companion object {
        const val MAX_VALUE = 100
    }
}
