package co.chainring.core.utils

import java.math.BigDecimal
import java.math.BigInteger

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}

fun BigDecimal.toFundamentalUnits(decimals: UByte): BigInteger {
    return this.toFundamentalUnits(decimals.toInt())
}

fun BigInteger.fromFundamentalUnits(decimals: Int): BigDecimal {
    return BigDecimal(this).movePointLeft(decimals)
}
