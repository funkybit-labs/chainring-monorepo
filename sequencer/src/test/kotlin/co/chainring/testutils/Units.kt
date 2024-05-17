package co.chainring.testutils

import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.IntegerValue
import java.math.BigDecimal
import java.math.BigInteger

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}

fun BigInteger.fromFundamentalUnits(decimals: Int): BigDecimal {
    return BigDecimal(this).movePointLeft(decimals)
}

fun IntegerValue.fromFundamentalUnits(decimals: Int): BigDecimal {
    return BigDecimal(this.toBigInteger()).movePointLeft(decimals)
}

fun BigDecimal.inWei() = toFundamentalUnits(18)
fun BigDecimal.inSats() = toFundamentalUnits(8)
