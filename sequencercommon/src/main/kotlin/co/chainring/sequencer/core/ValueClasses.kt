package co.chainring.sequencer.core

import java.math.BigDecimal

@JvmInline
value class WalletAddress(val value: Long) {
    override fun toString(): String = value.toString()
    companion object {
        val none = WalletAddress(0L)
    }
}

fun Long.toWalletAddress() = WalletAddress(this)

@JvmInline
value class OrderGuid(val value: Long) {
    override fun toString(): String = value.toString()
    companion object {
        val none = OrderGuid(0L)
    }
}

fun Long.toOrderGuid() = OrderGuid(this)

@JvmInline
value class MarketId(val value: String) {
    init {
        require(
            value.contains('/'),
        ) {
            "Invalid market ID"
        }
    }

    fun assets(): Pair<Asset, Asset> {
        val assets = value.split('/', limit = 2)
        return Pair(assets[0].toAsset(), assets[1].toAsset())
    }

    fun baseAsset() = value.split('/', limit = 2)[0].toAsset()

    fun quoteAsset() = value.split('/', limit = 2)[1].toAsset()

    override fun toString(): String = value
}

fun String.toMarketId() = MarketId(this)

@JvmInline
value class Asset(val value: String) {
    override fun toString(): String = value
}

fun String.toAsset() = Asset(this)

@JvmInline
value class FeeRate(val value: Long) {
    init {
        require(isValid(value)) { "Invalid fee rate" }
    }

    companion object {
        const val MIN_VALUE = 0L
        const val MAX_VALUE = 1000000L

        fun isValid(value: Long): Boolean =
            value in MIN_VALUE..MAX_VALUE

        val zero = FeeRate(0)

        // 1.0 means 1%
        fun fromPercents(percents: Double): FeeRate =
            FeeRate((BigDecimal(percents) * BigDecimal(MAX_VALUE) / BigDecimal(100)).toLong())
    }

    // 1% would be returned as 1.0
    fun inPercents(): Double =
        (100 * value).toDouble() / MAX_VALUE
}
