package xyz.funkybit.core.model.db

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.FeeRate

@Serializable
data class FeeRates(
    val maker: FeeRate,
    val taker: FeeRate,
) {
    companion object {
        fun fetch(): FeeRates =
            FeeRates(
                maker = FeeRate(KeyValueStore.getLong("MakerFeeRate") ?: 0),
                taker = FeeRate(KeyValueStore.getLong("TakerFeeRate") ?: 0),
            )

        fun fromPercents(maker: Double, taker: Double): FeeRates =
            FeeRates(
                maker = FeeRate.fromPercents(maker),
                taker = FeeRate.fromPercents(taker),
            )
    }

    fun persist() {
        KeyValueStore.setLong("MakerFeeRate", maker.value)
        KeyValueStore.setLong("TakerFeeRate", taker.value)
    }
}
