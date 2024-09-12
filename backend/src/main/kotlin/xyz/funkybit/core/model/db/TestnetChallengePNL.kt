package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Count
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.rowNumber
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import xyz.funkybit.apps.api.model.Leaderboard
import xyz.funkybit.apps.api.model.LeaderboardEntry
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Serializable
@JvmInline
value class TestnetChallengePNLId(override val value: String) : EntityId {
    companion object {
        fun generate(): TestnetChallengePNLId = TestnetChallengePNLId(TypeId.generate("tncpnl").toString())
    }

    override fun toString(): String = value
}

enum class TestnetChallengePNLType {
    DailyPNL,
    WeeklyPNL,
    OverallPNL,
}

object TestnetChallengePNLTable : GUIDTable<TestnetChallengePNLId>("testnet_challenge_pnl", ::TestnetChallengePNLId) {
    val asOf = timestamp("as_of").index()
    val userGuid = reference("user_guid", UserTable)
    val initialBalance = decimal("initial_balance", 30, 18)
    val currentBalance = decimal("current_balance", 30, 18)
    val type = customEnumeration(
        "type",
        "TestnetChallengePNLType",
        { value -> TestnetChallengePNLType.valueOf(value as String) },
        { PGEnum("TestnetChallengePNLType", it) },
    )

    init {
        uniqueIndex(
            customIndexName = "uix_testnet_challenge_pnl_type_user_guid",
            columns = arrayOf(type, userGuid),
        )
    }
}

class TestnetChallengePNLEntity(guid: EntityID<TestnetChallengePNLId>) : GUIDEntity<TestnetChallengePNLId>(guid) {
    companion object : EntityClass<TestnetChallengePNLId, TestnetChallengePNLEntity>(TestnetChallengePNLTable) {
        fun initializeForUser(user: UserEntity) {
            val now = Clock.System.now()
            TestnetChallengePNLType.entries.forEach { type ->
                TestnetChallengePNLTable.insert {
                    it[guid] = TestnetChallengePNLId.generate()
                    it[asOf] = now
                    it[userGuid] = user.guid
                    it[initialBalance] = TestnetChallengeUtils.depositAmount
                    it[currentBalance] = TestnetChallengeUtils.depositAmount
                    it[TestnetChallengePNLTable.type] = type
                }
            }
        }

        fun getLastUpdate(): LocalDateTime? {
            return TestnetChallengePNLTable
                .select(TestnetChallengePNLTable.asOf)
                .orderBy(TestnetChallengePNLTable.asOf to SortOrder.DESC)
                .limit(1).singleOrNull()
                ?.let {
                    it[TestnetChallengePNLTable.asOf].toLocalDateTime(TimeZone.UTC)
                }
        }

        fun updateAllBalances() {
            val referenceSymbol = TestnetChallengeUtils.depositSymbol()
            TransactionManager.current().exec(
                """
                WITH balance_summary AS (
                    SELECT 
                        u.${UserTable.guid.name} AS user_guid,
                        SUM(bal.${BalanceTable.balance.name} / POWER(10, ${SymbolTable.decimals.name}) * COALESCE(mkt.${MarketTable.feedPrice.name}, case when bal.${BalanceTable.symbolGuid.name} = '${referenceSymbol.guid.value}' then 1.0 else 0.0 end)) AS total_balance
                    FROM 
                        ${BalanceTable.tableName} AS bal
                    INNER JOIN 
                        ${WalletTable.tableName} AS w ON bal.${BalanceTable.walletGuid.name} = w.${WalletTable.guid.name}
                    INNER JOIN 
                        "${UserTable.tableName}" AS u ON w.${WalletTable.userGuid.name} = u.${UserTable.guid.name}
                    INNER JOIN
                        ${SymbolTable.tableName} AS sym ON bal.${BalanceTable.symbolGuid.name} = sym.${SymbolTable.id.name}
                    LEFT JOIN 
                        ${MarketTable.tableName} AS mkt ON
                          mkt.${MarketTable.baseSymbolGuid.name} = bal.${BalanceTable.symbolGuid.name} AND
                          mkt.${MarketTable.quoteSymbolGuid.name} = '${referenceSymbol.guid.value}'
                    WHERE ${BalanceTable.type.name} = '${BalanceType.Exchange}'
                    GROUP BY 
                        u.${UserTable.guid.name}
                )
                UPDATE ${TestnetChallengePNLTable.tableName} AS tcp
                SET 
                    ${TestnetChallengePNLTable.currentBalance.name} = bs.total_balance,
                    as_of = now()
                FROM 
                    balance_summary AS bs
                WHERE 
                    tcp.${TestnetChallengePNLTable.userGuid.name} = bs.user_guid;
                """.trimIndent(),
            )
        }

        fun distributePoints(challengePNLType: TestnetChallengePNLType) {
            val rankDef = rowNumber().over().partitionBy(TestnetChallengePNLTable.type).orderBy(TestnetChallengePNLTable.currentBalance, SortOrder.DESC).alias("rank")
            val totalUsersDef = Count(TestnetChallengePNLTable.userGuid).over().partitionBy(TestnetChallengePNLTable.type).alias("total_users")

            val rankedUsers = TestnetChallengePNLTable
                .select(TestnetChallengePNLTable.userGuid, TestnetChallengePNLTable.type, rankDef, totalUsersDef)
                .where { TestnetChallengePNLTable.type.eq(challengePNLType) }

            rankedUsers.forEach { row ->
                val userGuid = row[TestnetChallengePNLTable.userGuid]
                val rank = row[rankDef]
                val totalUsers = row[totalUsersDef]
                val timeGroup = row[TestnetChallengePNLTable.type]

                val points = when {
                    // top 1
                    rank == 1L -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 12_500
                        TestnetChallengePNLType.WeeklyPNL -> 50_000
                        TestnetChallengePNLType.OverallPNL -> 500_000
                    }
                    // top 1%
                    (rank - 1) <= totalUsers * 0.01 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 2_500
                        TestnetChallengePNLType.WeeklyPNL -> 10_000
                        TestnetChallengePNLType.OverallPNL -> 100_000
                    }
                    // top 5%
                    (rank - 1) <= totalUsers * 0.05 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 1_250
                        TestnetChallengePNLType.WeeklyPNL -> 5_000
                        TestnetChallengePNLType.OverallPNL -> 25_000
                    }
                    // top 10%
                    (rank - 1) <= totalUsers * 0.10 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 625
                        TestnetChallengePNLType.WeeklyPNL -> 2_500
                        TestnetChallengePNLType.OverallPNL -> 12_500
                    }
                    // top 25%
                    (rank - 1) <= totalUsers * 0.25 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 250
                        TestnetChallengePNLType.WeeklyPNL -> 1_000
                        TestnetChallengePNLType.OverallPNL -> 5_000
                    }
                    // top 50%
                    (rank - 1) <= totalUsers * 0.50 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 125
                        TestnetChallengePNLType.WeeklyPNL -> 500
                        TestnetChallengePNLType.OverallPNL -> 2_500
                    }
                    // bottom 1
                    rank == totalUsers -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 250
                        TestnetChallengePNLType.WeeklyPNL -> 1_000
                        TestnetChallengePNLType.OverallPNL -> 10_000
                    }
                    // bottom 5%
                    rank > totalUsers * 0.95 -> when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> 125
                        TestnetChallengePNLType.WeeklyPNL -> 500
                        TestnetChallengePNLType.OverallPNL -> 5_000
                    }
                    else -> 0
                }

                if (points > 0) {
                    when (timeGroup) {
                        TestnetChallengePNLType.DailyPNL -> TestnetChallengeUserRewardEntity.createDailyReward(userGuid.value, points.toBigDecimal())
                        TestnetChallengePNLType.WeeklyPNL -> TestnetChallengeUserRewardEntity.createWeeklyReward(userGuid.value, points.toBigDecimal())
                        TestnetChallengePNLType.OverallPNL -> TestnetChallengeUserRewardEntity.createOverallReward(userGuid.value, points.toBigDecimal())
                    }
                }
            }
        }

        fun resetCurrentBalance(challengePNLType: TestnetChallengePNLType) {
            TestnetChallengePNLTable.update({ TestnetChallengePNLTable.type.eq(challengePNLType) }) {
                it[initialBalance] = currentBalance
            }
        }

        fun getLeaderboard(testnetChallengePNLType: TestnetChallengePNLType, page: Long): Leaderboard {
            val count = TestnetChallengePNLTable
                .selectAll()
                .where { TestnetChallengePNLTable.type eq testnetChallengePNLType }
                .count()

            val rowsPerPage = 20
            val maxPage = ceil(count.div(rowsPerPage.toDouble())).toLong()
            // ensure page is same
            val normalizedPage = min(maxPage - 1, max(page, 0L))
            val pnlRatioExpr = (
                (TestnetChallengePNLTable.currentBalance - TestnetChallengePNLTable.initialBalance)
                    .div(TestnetChallengePNLTable.initialBalance)
                )
                .alias("pnl_percentage")
            val entries = TestnetChallengePNLTable.innerJoin(
                UserTable,
            ).join(
                WalletTable,
                JoinType.LEFT,
                UserTable.guid,
                WalletTable.userGuid,
                additionalConstraint = { WalletTable.networkType eq NetworkType.Evm },
            ).select(
                TestnetChallengePNLTable.id,
                TestnetChallengePNLTable.initialBalance,
                TestnetChallengePNLTable.currentBalance,
                UserTable.nickName,
                UserTable.avatarUrl,
                WalletTable.address,
                pnlRatioExpr,
            )
                .where { TestnetChallengePNLTable.type eq testnetChallengePNLType }
                .orderBy(pnlRatioExpr, SortOrder.DESC)
                .limit(rowsPerPage, offset = normalizedPage * rowsPerPage)
                .toList()
            return Leaderboard(
                type = testnetChallengePNLType,
                page = normalizedPage.toInt() + 1,
                lastPage = maxPage.toInt(),
                entries.map { entry ->
                    LeaderboardEntry(
                        entry[UserTable.nickName] ?: Address.auto(entry[WalletTable.address]).abbreviated(),
                        entry[UserTable.avatarUrl],
                        entry[TestnetChallengePNLTable.currentBalance].toDouble(),
                        entry[pnlRatioExpr].toDouble(),
                    )
                },
            )
        }
    }

    var asOf by TestnetChallengePNLTable.asOf
    var userGuid by TestnetChallengePNLTable.userGuid
    var user by UserEntity referencedOn TestnetChallengePNLTable.userGuid
    var initialBalance by TestnetChallengePNLTable.initialBalance
    var currentBalance by TestnetChallengePNLTable.currentBalance
    val type by TestnetChallengePNLTable.type
}
