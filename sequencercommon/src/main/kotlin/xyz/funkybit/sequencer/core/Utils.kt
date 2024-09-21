package xyz.funkybit.sequencer.core

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import xyz.funkybit.sequencer.proto.BalanceChange
import xyz.funkybit.sequencer.proto.DecimalValue
import xyz.funkybit.sequencer.proto.IntegerValue
import xyz.funkybit.sequencer.proto.balanceChange
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

fun sumBigIntegers(a: BigInteger, b: BigInteger): BigInteger = a + b
fun sumBaseAmounts(a: BaseAmount, b: BaseAmount): BaseAmount = a + b
fun sumQuoteAmounts(a: QuoteAmount, b: QuoteAmount): QuoteAmount = a + b

fun Iterable<BigInteger>.sum(): BigInteger = reduce(::sumBigIntegers)

fun notional(amount: BaseAmount, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int): QuoteAmount =
    (amount.toBigDecimal() * price).movePointRight(quoteDecimals - baseDecimals).toQuoteAmount()

fun notionalFee(notional: QuoteAmount, feeRate: FeeRate): QuoteAmount =
    (notional.value.toBigDecimal() * feeRate.value.toBigDecimal().setScale(30) / FeeRate.MAX_VALUE.toBigDecimal()).toQuoteAmount()

fun notionalPlusFee(amount: BaseAmount, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int, feeRate: FeeRate): QuoteAmount =
    (amount.value.toBigDecimal() * price).movePointRight(quoteDecimals - baseDecimals).toQuoteAmount().let { notional ->
        notional + notionalFee(notional, feeRate)
    }

fun quantityFromNotionalAndPrice(notional: QuoteAmount, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int): BaseAmount =
    (notional.toBigDecimal().setScale(18) / price).movePointRight(baseDecimals - quoteDecimals).toBaseAmount()

fun notionalPlusFee(amount: IntegerValue, price: DecimalValue, baseDecimals: Int, quoteDecimals: Int, feeRate: FeeRate): QuoteAmount =
    notionalPlusFee(amount.toBaseAmount(), price.toBigDecimal(), baseDecimals, quoteDecimals, feeRate)

fun Map<Pair<AccountGuid, Asset>, BigInteger>.asBalanceChangesList(): List<BalanceChange> =
    mapNotNull { (k, delta) ->
        if (delta != BigInteger.ZERO) {
            val (account, asset) = k
            balanceChange {
                this.account = account.value
                this.asset = asset.value
                this.delta = delta.toIntegerValue()
            }
        } else {
            null
        }
    }
