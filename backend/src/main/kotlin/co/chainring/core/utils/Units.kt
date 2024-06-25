package co.chainring.core.utils

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}

fun BigDecimal.toFundamentalUnits(decimals: UByte): BigInteger {
    return this.toFundamentalUnits(decimals.toInt())
}

fun BigInteger.fromFundamentalUnits(decimals: Int): BigDecimal {
    return BigDecimal(this).movePointLeft(decimals)
}

fun BigInteger.fromFundamentalUnits(decimals: UByte): BigDecimal {
    return BigDecimal(this).movePointLeft(decimals.toInt())
}

fun BigDecimal.setScale(decimals: UByte): BigDecimal =
    this.setScale(decimals.toInt())

fun BigDecimal.setScale(decimals: UByte, roundingMode: RoundingMode): BigDecimal =
    this.setScale(decimals.toInt(), roundingMode)

fun String.crPoints(): BigDecimal =
    BigDecimal(this).setScale(18)
