package xyz.funkybit.core.repeater.tasks

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.time.Duration.Companion.seconds

class TestnetChallengeLeaderboardTask : RepeaterBaseTask(
    invokePeriod = 10.seconds,
) {
    override val name: String = "testnet_challenge_leaderboard"

    override fun runWithLock() {
        if (TestnetChallengeUtils.enabled) {
            transaction {
                // get the last update from the pnl table
                val lastUpdate = TestnetChallengePNLEntity.getLastUpdate()

                if (lastUpdate != null) {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

                    // if we've crossed a day boundary, reward points and reset the leaderboards
                    if (now.date == lastUpdate.date.plus(1, DateTimeUnit.DAY)) {
                        TestnetChallengePNLEntity.distributePoints(TestnetChallengePNLType.DailyPNL)
                        TestnetChallengePNLEntity.resetCurrentBalance(TestnetChallengePNLType.DailyPNL)

                        // and also week boundary
                        if (lastUpdate.date.dayOfWeek == DayOfWeek.SUNDAY && now.date.dayOfWeek == DayOfWeek.MONDAY) {
                            TestnetChallengePNLEntity.distributePoints(TestnetChallengePNLType.WeeklyPNL)
                            TestnetChallengePNLEntity.resetCurrentBalance(TestnetChallengePNLType.WeeklyPNL)
                        }
                    }
                }

                TestnetChallengePNLEntity.updateAllBalances()
            }
        }
    }
}
