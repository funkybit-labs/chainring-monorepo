package xyz.funkybit.sequencer.core

import com.google.protobuf.kotlin.toByteString
import xyz.funkybit.sequencer.proto.IntegerValue
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

@JvmInline
value class WalletAddress(val value: Long) {
    override fun toString(): String = value.toString()
    companion object {
        val none = WalletAddress(0L)
    }
}
fun Long.toWalletAddress() = WalletAddress(this)

@JvmInline
value class AccountGuid(val value: Long) {
    override fun toString(): String = value.toString()
    companion object {
        val none = AccountGuid(0L)
    }
}
fun Long.toAccountGuid() = AccountGuid(this)

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

@JvmInline
value class QuoteAmount(val value: BigInteger) {
    constructor(value: String) : this(value.toBigInteger())

    operator fun compareTo(quantity: QuoteAmount) = this.value.compareTo(quantity.value)
    operator fun plus(other: QuoteAmount) = QuoteAmount(this.value + other.value)
    operator fun minus(other: QuoteAmount) = QuoteAmount(this.value - other.value)
    operator fun times(other: QuoteAmount) = QuoteAmount(this.value * other.value)
    operator fun div(other: QuoteAmount) = QuoteAmount(this.value / other.value)
    operator fun unaryMinus() = QuoteAmount(-this.value)
    fun toByteArray(): ByteArray = this.value.toByteArray()
    fun toBigInteger() = this.value
    fun toBigDecimal() = BigDecimal(this.value)
    fun toIntegerValue(): IntegerValue = IntegerValue.newBuilder()
        .setValue(this.toByteArray().toByteString())
        .build()
    fun toBaseAmount() = BaseAmount(this.value)
    fun min(other: QuoteAmount) = QuoteAmount(this.value.min(other.value))
    fun max(other: QuoteAmount) = QuoteAmount(this.value.max(other.value))
    fun negate() = QuoteAmount(this.value.negate())

    companion object {
        val ZERO = QuoteAmount(BigInteger.ZERO)
    }
}

@JvmInline
value class BaseAmount(val value: BigInteger) {
    constructor(value: String) : this(value.toBigInteger())

    operator fun compareTo(quantity: BaseAmount) = this.value.compareTo(quantity.value)
    operator fun plus(other: BaseAmount) = BaseAmount(this.value + other.value)
    operator fun minus(other: BaseAmount) = BaseAmount(this.value - other.value)
    operator fun times(other: BaseAmount) = BaseAmount(this.value * other.value)
    operator fun div(other: BaseAmount) = BaseAmount(this.value / other.value)
    operator fun unaryMinus() = BaseAmount(-this.value)
    fun toByteArray(): ByteArray = this.value.toByteArray()
    fun toBigInteger() = this.value
    fun toBigDecimal() = BigDecimal(this.value)
    fun toIntegerValue(): IntegerValue = IntegerValue.newBuilder()
        .setValue(this.toByteArray().toByteString())
        .build()
    fun toQuoteAmount() = QuoteAmount(this.value)
    fun min(other: BaseAmount) = BaseAmount(this.value.min(other.value))
    fun max(other: BaseAmount) = BaseAmount(this.value.max(other.value))
    fun negate() = BaseAmount(this.value.negate())

    companion object {
        val ZERO = BaseAmount(BigInteger.ZERO)
    }
}

fun BigDecimal.toQuoteAmount() = QuoteAmount(this.toBigInteger())
fun BigDecimal.toBaseAmount() = BaseAmount(this.setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact())
fun BigInteger.toQuoteAmount() = QuoteAmount(this)
fun BigInteger.toBaseAmount() = BaseAmount(this)
fun IntegerValue.toQuoteAmount() = QuoteAmount(this.toBigInteger())
fun IntegerValue.toBaseAmount() = BaseAmount(this.toBigInteger())
