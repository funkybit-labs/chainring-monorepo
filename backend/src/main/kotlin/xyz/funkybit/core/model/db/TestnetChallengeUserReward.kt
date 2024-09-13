package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.sum
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
        fun createDailyReward(user: UserEntity, amount: BigDecimal) {
            create(user, TestnetChallengeUserRewardType.DailyReward, amount)
        }

        fun createWeeklyReward(user: UserEntity, amount: BigDecimal) {
            create(user, TestnetChallengeUserRewardType.WeeklyReward, amount)
        }

        fun createOverallReward(user: UserEntity, amount: BigDecimal) {
            create(user, TestnetChallengeUserRewardType.OverallReward, amount)
        }

        fun createReferralBonusReward(user: UserEntity, amount: BigDecimal) {
            create(user, TestnetChallengeUserRewardType.ReferralBonus, amount, by = "system")
        }

        private fun create(user: UserEntity, type: TestnetChallengeUserRewardType, amount: BigDecimal, by: String = user.guid.value.value) {
            val now = Clock.System.now()

            val previousBalance = user.pointsBalances().sum()
            val newBalance = previousBalance + amount

            TestnetChallengeUserRewardTable.insertIgnore {
                it[guid] = EntityID(TestnetChallengeUserRewardId.generate(), TestnetChallengeUserRewardTable)
                it[userGuid] = user.guid
                it[createdAt] = now
                it[createdBy] = by
                it[TestnetChallengeUserRewardTable.type] = type
                it[TestnetChallengeUserRewardTable.amount] = amount
            }
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
