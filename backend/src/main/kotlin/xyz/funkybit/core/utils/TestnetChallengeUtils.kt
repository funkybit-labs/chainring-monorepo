package xyz.funkybit.core.utils

import xyz.funkybit.core.model.db.SymbolEntity
import java.math.BigDecimal
import kotlin.random.Random

object TestnetChallengeUtils {
    val enabled = System.getenv("TESTNET_CHALLENGE_ENABLED")?.toBoolean() ?: true
    val depositSymbolName: String = System.getenv("TESTNET_CHALLENGE_DEPOSIT_SYMBOL") ?: "USDC:1337"
    fun inviteCode() = (0..10).map { Random.nextInt(65, 91).toChar() }.joinToString("")
    fun depositSymbol() = SymbolEntity.forName(depositSymbolName)
    val depositAmount = System.getenv("TESTNET_CHALLENGE_DEPOSIT_AMOUNT")?.toBigDecimal() ?: BigDecimal.valueOf(10000L)
    val gasDepositAmount = System.getenv("TESTNET_CHALLENGE_GAS_AMOUNT")?.toBigDecimal() ?: BigDecimal.valueOf(0.01)
}
