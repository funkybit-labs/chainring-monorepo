package co.chainring.sequencer.core

import co.chainring.sequencer.proto.DecimalValue
import co.chainring.sequencer.proto.IntegerValue
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

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