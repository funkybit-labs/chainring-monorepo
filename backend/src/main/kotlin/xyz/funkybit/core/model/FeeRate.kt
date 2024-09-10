package xyz.funkybit.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
@JvmInline
value class FeeRate(val value: Long) {
    init {
        require(value in 0..MAX_VALUE) { "Invalid fee rate $value" }
    }

    companion object {
        const val MAX_VALUE = 1_000_000L

        fun fromPercents(percents: BigDecimal): FeeRate =
            FeeRate((percents * BigDecimal(MAX_VALUE)).toLong())
    }

    fun toPercents(): BigDecimal =
        BigDecimal(value).setScale(6) / BigDecimal(MAX_VALUE)
}
