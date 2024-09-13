package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V87_TestnetChallengeRewardCategory : Migration() {

    enum class V87_TestnetChallengeRewardCategory {
        Top1,
        Top1Percent,
        Top5Percent,
        Top10Percent,
        Top25Percent,
        Top50Percent,
        Bottom5Percent,
        Bottom1,
    }

    @Suppress("ClassName")
    object V87_TestnetChallengeUserRewardTable : GUIDTable<TestnetChallengeUserRewardId>("testnet_challenge_user_reward", ::TestnetChallengeUserRewardId) {
        val rewardCategory = customEnumeration(
            "reward_category",
            "TestnetChallengeRewardCategory",
            { value -> V87_TestnetChallengeRewardCategory.valueOf(value as String) },
            { PGEnum("TestnetChallengeRewardCategory", it) },
        ).nullable()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE TestnetChallengeRewardCategory AS ENUM (${enumDeclaration<V87_TestnetChallengeRewardCategory>()})")

            SchemaUtils.createMissingTablesAndColumns(V87_TestnetChallengeUserRewardTable)
        }
    }
}
