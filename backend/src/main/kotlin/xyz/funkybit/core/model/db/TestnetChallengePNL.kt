package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
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
            val rewardType = when (challengePNLType) {
                TestnetChallengePNLType.DailyPNL -> TestnetChallengeUserRewardType.DailyReward
                TestnetChallengePNLType.WeeklyPNL -> TestnetChallengeUserRewardType.WeeklyReward
                TestnetChallengePNLType.OverallPNL -> TestnetChallengeUserRewardType.OverallReward
            }

            TransactionManager.current().exec(
                """
                WITH ranked_users AS (SELECT ${TestnetChallengePNLTable.userGuid.name}   AS user_guid,
                                             ${TestnetChallengePNLTable.type.name}       AS type,
                                             ROW_NUMBER() OVER (
                                                PARTITION BY type 
                                                ORDER BY ((${TestnetChallengePNLTable.currentBalance.name} - ${TestnetChallengePNLTable.initialBalance.name}) / ${TestnetChallengePNLTable.initialBalance.name}) DESC
                                             )                                           AS rank,
                                             COUNT(*) OVER (PARTITION BY type)           AS total_users
                                      FROM ${TestnetChallengePNLTable.tableName}
                                      WHERE type = '${challengePNLType.name}'),
                     calculated_points AS (SELECT user_guid,
                                                  type,
                                                  rank,
                                                  total_users,
                                                  CASE
                                                      -- first
                                                      WHEN rank = 1 THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 12500
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 50000
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 500000
                                                          END
                                                      -- last
                                                      WHEN rank = total_users THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 250
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 1000
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 10000
                                                          END
                                                      -- top 1%
                                                      WHEN (rank - 1) <= total_users * 0.01 THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 2500
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 10000
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 100000
                                                          END
                                                      -- top 5%
                                                      WHEN (rank - 1) <= total_users * 0.05 THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 1250
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 5000
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 25000
                                                          END
                                                      -- top 10%
                                                      WHEN (rank - 1) <= total_users * 0.10 THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 625
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 2500
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 12500
                                                          END
                                                      -- top 25%
                                                      WHEN (rank - 1) <= total_users * 0.25 THEN
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 250
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 1000 
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 5000 
                                                          END
                                                      -- top 50%
                                                      WHEN (rank - 1) <= total_users * 0.50 THEN 
                                                          CASE type 
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 125 
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 500
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 2500
                                                          END
                                                      -- bottom 5%
                                                      WHEN rank > total_users * 0.95 THEN 
                                                          CASE type
                                                              WHEN '${TestnetChallengePNLType.DailyPNL.name}' THEN 125
                                                              WHEN '${TestnetChallengePNLType.WeeklyPNL.name}' THEN 500
                                                              WHEN '${TestnetChallengePNLType.OverallPNL.name}' THEN 5000
                                                          END
                                                      -- rest
                                                      ELSE 0 END AS points
                                           FROM ranked_users)
                INSERT
                INTO ${TestnetChallengeUserRewardTable.tableName}(
                    ${TestnetChallengeUserRewardTable.guid.name}, 
                    ${TestnetChallengeUserRewardTable.createdAt.name}, 
                    ${TestnetChallengeUserRewardTable.createdBy.name}, 
                    ${TestnetChallengeUserRewardTable.userGuid.name}, 
                    ${TestnetChallengeUserRewardTable.type.name}, 
                    ${TestnetChallengeUserRewardTable.amount.name}
                )
                SELECT typeid_generate_text('tncurwd'), now(), 'system', user_guid, '${rewardType.name}', points
                FROM calculated_points
                WHERE points > 0;
                """.trimIndent(),
            )
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
