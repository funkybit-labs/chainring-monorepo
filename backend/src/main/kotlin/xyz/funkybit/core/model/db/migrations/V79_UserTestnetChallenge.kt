package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardId
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V79_UserTestnetChallenge : Migration() {
    @Serializable
    enum class V79_TestnetChallengeStatus {
        Unenrolled,
        PendingAirdrop,
        PendingDeposit,
        PendingDepositConfirmation,
        Enrolled,
        Disqualified,
    }

    object V79_UserTable : GUIDTable<UserId>("user", ::UserId) {
        val nickName = varchar("nick_name", 10485760).nullable()
        val avatarUrl = varchar("avatar_url", 10485760).nullable()
        val testnetChallengeStatus = customEnumeration(
            "testnet_challenge_status",
            "TestnetChallengeStatus",
            { value -> V79_TestnetChallengeStatus.valueOf(value as String) },
            { PGEnum("TestnetChallengeStatus", it) },
        ).index().nullable()
        val inviteCode = varchar("invite_code", 10485760).index().nullable()
        val invitedBy = reference("invited_by", V79_UserTable).index().nullable()
        val testnetAirdropTxHash = varchar("testnet_airdrop_tx_hash", 10485760).nullable()
    }

    enum class V79_TestnetChallengeUserRewardType {
        DailyReward,
        WeeklyReward,
        OverallReward,
        ReferralBonus,
    }

    object V79_TestnetChallengeUserRewardTable : GUIDTable<TestnetChallengeUserRewardId>("testnet_challenge_user_reward", ::TestnetChallengeUserRewardId) {
        val createdAt = timestamp("created_at").index()
        val createdBy = varchar("created_by", 10485760)
        val userGuid = reference("user_guid", V79_UserTable).index()
        val amount = decimal("amount", 30, 18)
        val type = customEnumeration(
            "type",
            "TestnetChallengeUserRewardType",
            { value -> V79_TestnetChallengeUserRewardType.valueOf(value as String) },
            { PGEnum("TestnetChallengeUserRewardType", it) },
        ).index()
    }

    override fun run() {
        transaction {
            // add user table
            exec("CREATE TYPE TestnetChallengeStatus AS ENUM (${enumDeclaration<V79_TestnetChallengeStatus>()})")
            exec("CREATE TYPE TestnetChallengeUserRewardType AS ENUM (${enumDeclaration<V79_TestnetChallengeUserRewardType>()})")
            SchemaUtils.createMissingTablesAndColumns(V79_UserTable, V79_TestnetChallengeUserRewardTable)
            exec("""UPDATE "user" SET testnet_challenge_status = 'Unenrolled'""")
            exec("""ALTER TABLE "user" ALTER COLUMN testnet_challenge_status SET NOT NULL""")
            exec("""UPDATE "user" SET invite_code = (array_to_string(array(select chr((65 + floor(random() * 26)::int)::int) from generate_series(1, 10)), ''))::text""")
            exec("""ALTER TABLE "user" ALTER COLUMN invite_code SET NOT NULL""")
        }
    }
}
