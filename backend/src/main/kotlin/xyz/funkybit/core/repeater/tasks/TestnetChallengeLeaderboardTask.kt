package xyz.funkybit.core.repeater.tasks

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLTable
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.time.Duration.Companion.seconds

class TestnetChallengeLeaderboardTask() : RepeaterBaseTask(
    invokePeriod = 10.seconds,
) {
    override val name: String = "testnet_challenge_leaderboard"

    override fun runWithLock() {
        if (TestnetChallengeUtils.enabled) {
            transaction {
                // get the last update from the pnl table
                val lastUpdate = TestnetChallengePNLTable.select(TestnetChallengePNLTable.asOf).orderBy(TestnetChallengePNLTable.asOf to SortOrder.DESC).limit(1).singleOrNull()?.let {
                    it[TestnetChallengePNLTable.asOf].toLocalDateTime(TimeZone.UTC)
                }
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                // if we've crossed a day boundary, reward points and reset the leaderboards
                if (lastUpdate != null && (now.dayOfYear - 1 == lastUpdate.dayOfYear || (now.dayOfYear == 1 && now.year - 1 == lastUpdate.year))) {
                    // TODO - CHAIN-479
                }
                TestnetChallengePNLEntity.updateAllBalances()
            }
        }
    }
}
