package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.math.BigDecimal

@Serializable
@JvmInline
value class TestnetChallengeUserRewardId(override val value: String) : EntityId {
    companion object {
        fun generate(): TestnetChallengeUserRewardId = TestnetChallengeUserRewardId(TypeId.generate("tncurwd").toString())
    }

    override fun toString(): String = value
}

enum class TestnetChallengeUserRewardType {
    DailyReward,
    WeeklyReward,
    OverallReward,
    ReferralBonus,
}

enum class TestnetChallengeRewardCategory {
    Top1,
    Top1Percent,
    Top5Percent,
    Top10Percent,
    Top25Percent,
    Top50Percent,
    Bottom5Percent,
    Bottom1,
}

object TestnetChallengeUserRewardTable : GUIDTable<TestnetChallengeUserRewardId>("testnet_challenge_user_reward", ::TestnetChallengeUserRewardId) {
    val createdAt = timestamp("created_at").index()
    val createdBy = varchar("created_by", 10485760)
    val userGuid = reference("user_guid", UserTable).index()
    val amount = decimal("amount", 30, 18)
    val type = customEnumeration(
        "type",
        "TestnetChallengeUserRewardType",
        { value -> TestnetChallengeUserRewardType.valueOf(value as String) },
        { PGEnum("TestnetChallengeUserRewardType", it) },
    ).index()
    val rewardCategory = customEnumeration(
        "reward_category",
        "TestnetChallengeRewardCategory",
        { value -> TestnetChallengeRewardCategory.valueOf(value as String) },
        { PGEnum("TestnetChallengeRewardCategory", it) },
    ).nullable()
}

class TestnetChallengeUserRewardEntity(guid: EntityID<TestnetChallengeUserRewardId>) : GUIDEntity<TestnetChallengeUserRewardId>(guid) {
    companion object : EntityClass<TestnetChallengeUserRewardId, TestnetChallengeUserRewardEntity>(TestnetChallengeUserRewardTable) {

        fun distributeReferralPoints(
            earnedAfter: Instant,
            earnedBefore: Instant,
            referralBonusSize: BigDecimal,
            referralBonusMinAmount: BigDecimal,
        ) {
            TransactionManager.current().exec(
                """
                    WITH RECURSIVE referral_chain(invitee_guid, invitee_points, invitor_guid, invitor_points, level) AS (
                        -- initial data set - direct referrer
                        SELECT
                            r.${TestnetChallengeUserRewardTable.userGuid.name} AS invitee_guid,
                            r.${TestnetChallengeUserRewardTable.amount.name}::numeric AS invitee_points,
                            u.${UserTable.invitedBy.name} AS invitor_guid,
                            r.${TestnetChallengeUserRewardTable.amount.name} * $referralBonusSize AS invitor_points,        -- 'referralBonusFraction' of the previous bonus
                            1 AS level
                        FROM ${TestnetChallengeUserRewardTable.tableName} r
                                 JOIN "${UserTable.tableName}" u ON r.user_guid = u.guid
                        WHERE u.${UserTable.invitedBy.name} IS NOT NULL
                          AND r.${TestnetChallengeUserRewardTable.type.name} != '${TestnetChallengeUserRewardType.ReferralBonus.name}'
                          AND r.${TestnetChallengeUserRewardTable.createdAt.name} > '$earnedAfter'
                          AND r.${TestnetChallengeUserRewardTable.createdAt.name} <= '$earnedBefore'

                        UNION ALL

                        -- recursive data: next level in the referral chain
                        SELECT
                            rc.invitee_guid,
                            rc.invitee_points,
                            u.${UserTable.invitedBy.name} as invitor_guid,
                            rc.invitor_points * $referralBonusSize AS invitor_points,                                       -- 'referralBonusFraction' of the previous bonus
                            rc.level + 1 AS level
                        FROM referral_chain rc JOIN "${UserTable.tableName}" u ON rc.invitor_guid = u.${UserTable.guid.name}
                        WHERE u.${UserTable.invitedBy.name} IS NOT NULL                                                         -- stop when no invitor
                          AND rc.invitor_points * $referralBonusSize >= $referralBonusMinAmount                             -- stop when bonus becomes insignificant
                    )
                    INSERT
                    INTO ${TestnetChallengeUserRewardTable.tableName}(
                        ${TestnetChallengeUserRewardTable.guid.name}, 
                        ${TestnetChallengeUserRewardTable.createdAt.name}, 
                        ${TestnetChallengeUserRewardTable.createdBy.name}, 
                        ${TestnetChallengeUserRewardTable.userGuid.name}, 
                        ${TestnetChallengeUserRewardTable.type.name}, 
                        ${TestnetChallengeUserRewardTable.amount.name},
                        ${TestnetChallengeUserRewardTable.rewardCategory.name}
                    )
                    SELECT 
                        typeid_generate_text('tncurwd'), 
                        now(), 
                        'system', 
                        invitor_guid, 
                        '${TestnetChallengeUserRewardType.ReferralBonus.name}', 
                        sum(invitor_points), 
                        null 
                    FROM referral_chain 
                    GROUP BY invitor_guid
                """.trimIndent(),
            )
        }

        fun inviteePointsPerInviter(from: Instant, to: Instant): Map<UserId, BigDecimal> {
            return TestnetChallengeUserRewardTable
                .join(UserTable, JoinType.LEFT, UserTable.guid, TestnetChallengeUserRewardTable.userGuid)
                .select(UserTable.invitedBy, TestnetChallengeUserRewardTable.amount.sum())
                .where {
                    listOf(
                        UserTable.invitedBy.isNotNull(),
                        UserTable.testnetChallengeStatus.eq(TestnetChallengeStatus.Enrolled),
                        TestnetChallengeUserRewardTable.createdAt greater from,
                        TestnetChallengeUserRewardTable.createdAt lessEq to,
                    ).compoundAnd()
                }
                .groupBy(UserTable.invitedBy)
                .associate {
                    it[UserTable.invitedBy]!!.value to (
                        it[TestnetChallengeUserRewardTable.amount.sum()]?.setScale(18)
                            ?: BigDecimal.ZERO
                        )
                }
        }

        fun findRecentForUser(user: UserEntity): List<TestnetChallengeUserRewardEntity> {
            return TestnetChallengeUserRewardEntity.find {
                TestnetChallengeUserRewardTable.userGuid eq user.guid
            }
                .orderBy(TestnetChallengeUserRewardTable.createdAt to SortOrder.DESC)
                .limit(3)
                .toList()
        }
    }

    var createdAt by TestnetChallengeUserRewardTable.createdAt
    var createdBy by TestnetChallengeUserRewardTable.createdBy
    var userGuid by TestnetChallengeUserRewardTable.userGuid
    var user by UserEntity referencedOn TestnetChallengeUserRewardTable.userGuid
    var amount by TestnetChallengeUserRewardTable.amount
    var type by TestnetChallengeUserRewardTable.type
    var rewardCategory by TestnetChallengeUserRewardTable.rewardCategory
}

fun UserEntity.pointsBalances(): Map<TestnetChallengeUserRewardType, BigDecimal> {
    val sumColumn = TestnetChallengeUserRewardTable.amount.sum().alias("amount_sum")
    val typeColumn = TestnetChallengeUserRewardTable.type
    return TestnetChallengeUserRewardTable
        .select(typeColumn, sumColumn)
        .where { TestnetChallengeUserRewardTable.userGuid.eq(guid) }
        .groupBy(typeColumn)
        .associate {
            it[typeColumn] to (it[sumColumn]?.setScale(18) ?: BigDecimal.ZERO)
        }
}

fun Map<TestnetChallengeUserRewardType, BigDecimal>.sum(): BigDecimal {
    return this.values.fold(BigDecimal.ZERO) { acc: BigDecimal, balance: BigDecimal -> acc + balance }
}

fun Map<TestnetChallengeUserRewardType, BigDecimal>.ofType(type: TestnetChallengeUserRewardType): BigDecimal {
    return this[type] ?: BigDecimal.ZERO
}
