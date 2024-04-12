package co.chainring.core.utils

import java.math.BigDecimal
import java.math.BigInteger

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return (this * BigDecimal("1e$decimals")).toBigInteger()
}

fun BigDecimal.toFundamentalUnits(decimals: UByte): BigInteger {
    return (this * BigDecimal("1e$decimals")).toBigInteger()
}

fun BigInteger.fromFundamentalUnits(decimals: Int): BigDecimal {
    return (BigDecimal(this).setScale(decimals) / BigDecimal("1e$decimals"))
}
