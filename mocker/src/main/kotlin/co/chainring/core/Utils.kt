package co.chainring.core

import java.math.BigDecimal
import java.math.BigInteger

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}

fun Int.toFundamentalUnits(decimals: Int): BigInteger {
    return this.toBigInteger().toBigDecimal().toFundamentalUnits(decimals)
}
