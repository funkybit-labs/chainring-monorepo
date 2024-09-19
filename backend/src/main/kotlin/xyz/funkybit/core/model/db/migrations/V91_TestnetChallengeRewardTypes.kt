package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.db.updateEnum
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardId

@Suppress("ClassName")
class V91_TestnetChallengeRewardTypes : Migration() {

    enum class V91_TestnetChallengeUserRewardType {
        DailyReward,
        WeeklyReward,
        OverallReward,
        ReferralBonus,
        EvmWalletConnected,
        EvmWithdrawalDeposit,
        BitcoinWalletConnected,
        BitcoinWithdrawalDeposit,
    }

    object V91_TestnetChallengeUserRewardTable : GUIDTable<TestnetChallengeUserRewardId>("testnet_challenge_user_reward", ::TestnetChallengeUserRewardId) {
        val type = customEnumeration(
            "type",
            "TestnetChallengeUserRewardType",
            { value -> V91_TestnetChallengeUserRewardType.valueOf(value as String) },
            { PGEnum("TestnetChallengeUserRewardType", it) },
        ).index()
    }

    override fun run() {
        transaction {
            updateEnum<V91_TestnetChallengeUserRewardType>(listOf(V91_TestnetChallengeUserRewardTable.type), "TestnetChallengeUserRewardType")
        }
    }
}
