package xyz.funkybit.core.utils

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.sum
import xyz.funkybit.apps.api.model.TestnetChallengeDepositLimit
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.SymbolTable
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.WithdrawalTable
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

object TestnetChallengeUtils {
    val enabled = System.getenv("TESTNET_CHALLENGE_ENABLED")?.toBoolean() ?: true
    val depositSymbolName: String = System.getenv("TESTNET_CHALLENGE_DEPOSIT_SYMBOL") ?: "USDC:1337"
    fun inviteCode() = (0..10).map { Random.nextInt(65, 91).toChar() }.joinToString("")
    fun depositSymbol() = SymbolEntity.forName(depositSymbolName)
    val depositAmount = System.getenv("TESTNET_CHALLENGE_DEPOSIT_AMOUNT")?.toBigDecimal() ?: BigDecimal.valueOf(10000L)
    val gasDepositAmount = System.getenv("TESTNET_CHALLENGE_GAS_AMOUNT")?.toBigDecimal() ?: BigDecimal.valueOf(0.02)

    fun depositLimits(user: UserEntity): List<TestnetChallengeDepositLimit> {
        if (user.testnetChallengeStatus != TestnetChallengeStatus.Enrolled) {
            return emptyList()
        }

        val symbols = SymbolEntity.all().orderBy(Pair(SymbolTable.name, SortOrder.ASC)).toList()

        val depositSymbol = symbols.first { it.name == depositSymbolName }

        val withdrawalAmountsSumColumn = WithdrawalTable.amount.sum()
        val withdrawalTotalsBySymbol = WithdrawalTable
            .innerJoin(WalletTable)
            .innerJoin(SymbolTable)
            .select(SymbolTable.guid, withdrawalAmountsSumColumn)
            .where { WalletTable.userGuid.eq(user.guid) }
            .andWhere { WithdrawalTable.status.eq(WithdrawalStatus.Complete) }
            .groupBy(SymbolTable.guid)
            .associateBy(
                keySelector = { it[SymbolTable.guid] },
                valueTransform = { it[withdrawalAmountsSumColumn]?.toBigInteger() ?: BigInteger.ZERO },
            )

        val depositAmountsSumColumn = DepositTable.amount.sum()
        val depositTotalsBySymbol = DepositTable
            .innerJoin(WalletTable)
            .innerJoin(SymbolTable)
            .select(SymbolTable.guid, depositAmountsSumColumn)
            .where { WalletTable.userGuid.eq(user.guid) }
            .andWhere { DepositTable.status.neq(DepositStatus.Failed) }
            .groupBy(SymbolTable.guid)
            .associateBy(
                keySelector = { it[SymbolTable.guid] },
                valueTransform = { it[depositAmountsSumColumn]?.toBigInteger() ?: BigInteger.ZERO },
            )

        return symbols.map { symbol ->
            val withdrawalsTotal = withdrawalTotalsBySymbol.getOrDefault(symbol.guid, BigInteger.ZERO)
            var depositsTotal = depositTotalsBySymbol.getOrDefault(symbol.guid, BigInteger.ZERO)

            if (symbol.guid == depositSymbol.guid) {
                depositsTotal -= depositAmount.movePointRight(depositSymbol.decimals.toInt()).toBigInteger()
            }

            TestnetChallengeDepositLimit(Symbol(symbol.name), BigInteger.ZERO.max(withdrawalsTotal - depositsTotal))
        }.sortedBy { it.symbol.value }
    }
}
