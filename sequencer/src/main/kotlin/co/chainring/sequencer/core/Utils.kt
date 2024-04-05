package co.chainring.sequencer.core

import co.chainring.sequencer.proto.DecimalValue
import co.chainring.sequencer.proto.IntegerValue
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.math.pow

fun BigDecimal.toDecimalValue(): DecimalValue = DecimalValue.newBuilder()
    .setScale(this.scale())
    .setPrecision(this.precision())
    .setValue(ByteString.copyFrom(this.unscaledValue().toByteArray()))
    .build()

fun DecimalValue.toBigDecimal(): BigDecimal = BigDecimal(
    BigInteger(this.value.toByteArray()),
    this.scale,
    MathContext(this.precision),
)

fun BigInteger.toIntegerValue(): IntegerValue = IntegerValue.newBuilder()
    .setValue(this.toByteArray().toByteString())
    .build()

fun IntegerValue.toBigInteger(): BigInteger = BigInteger(
    this.value.toByteArray(),
)

fun sumBigIntegers(a: BigInteger, b: BigInteger) = a + b

fun notional(amount: BigInteger, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int) =
    (amount.toBigDecimal() * price * 10.0.pow((quoteDecimals - baseDecimals).toDouble()).toBigDecimal()).toBigInteger()

fun notional(amount: IntegerValue, price: DecimalValue, baseDecimals: Int, quoteDecimals: Int) =
    notional(amount.toBigInteger(), price.toBigDecimal(), baseDecimals, quoteDecimals)
