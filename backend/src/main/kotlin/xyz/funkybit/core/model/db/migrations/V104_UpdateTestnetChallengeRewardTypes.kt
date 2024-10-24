package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardId

@Suppress("ClassName")
class V104_UpdateTestnetChallengeRewardTypes : Migration() {
    enum class V104_TestnetChallengeUserRewardType {
        DailyReward,
        WeeklyReward,
        OverallReward,
        ReferralBonus,
        EvmWalletConnected,
        EvmWithdrawalDeposit,
        BitcoinWalletConnected,
        BitcoinWithdrawalDeposit,
        DiscordAccountLinked,
        XAccountLinked,
    }

    object V104_TestnetChallengeUserRewardTable : GUIDTable<TestnetChallengeUserRewardId>("testnet_challenge_user_reward", ::TestnetChallengeUserRewardId) {
        val type = customEnumeration(
            "type",
            "TestnetChallengeUserRewardType",
            { value -> V104_TestnetChallengeUserRewardType.valueOf(value as String) },
            { PGEnum("TestnetChallengeUserRewardType", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V104_TestnetChallengeUserRewardType>(listOf(V104_TestnetChallengeUserRewardTable.type), "TestnetChallengeUserRewardType")
        }
    }
}
