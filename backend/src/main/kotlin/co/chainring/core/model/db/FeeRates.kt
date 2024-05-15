package co.chainring.core.model.db

import co.chainring.core.model.FeeRate
import kotlinx.serialization.Serializable

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
