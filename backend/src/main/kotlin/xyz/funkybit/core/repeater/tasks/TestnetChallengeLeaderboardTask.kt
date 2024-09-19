package xyz.funkybit.core.repeater.tasks

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.KeyValueStore
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.time.Duration.Companion.seconds

class TestnetChallengeLeaderboardTask : RepeaterBaseTask(
    invokePeriod = 10.seconds,
) {
    override val name: String = "testnet_challenge_leaderboard"

    private val dailyPlnPointsKey: String = "daily_pln_points_granted_at"
    private val weeklyPlnPointsKey: String = "weekly_pln_points_granted_at"

    override fun runWithLock() {
        if (TestnetChallengeUtils.enabled) {
            transaction {
                // get the last update from the pnl table
                val lastUpdate = TestnetChallengePNLEntity.getLastUpdate()

                if (lastUpdate != null) {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val lastUpdateInstant = lastUpdate.toInstant(TimeZone.UTC)

                    // if we've crossed a day boundary, reward points and reset the leaderboards
                    if (now.date == lastUpdate.date.plus(1, DateTimeUnit.DAY)) {
                        val lastDailyPointsRun = KeyValueStore.getInstant(dailyPlnPointsKey) ?: Instant.fromEpochMilliseconds(0)
                        TestnetChallengePNLEntity.distributePoints(TestnetChallengePNLType.DailyPNL, intervalStart = lastDailyPointsRun, intervalEnd = lastUpdateInstant)
                        TestnetChallengePNLEntity.resetCurrentBalance(TestnetChallengePNLType.DailyPNL)
                        KeyValueStore.setInstant(dailyPlnPointsKey, lastUpdateInstant)

                        // and also week boundary
                        if (lastUpdate.date.dayOfWeek == DayOfWeek.SUNDAY && now.date.dayOfWeek == DayOfWeek.MONDAY) {
                            val lastWeeklyPointsRun = KeyValueStore.getInstant(weeklyPlnPointsKey) ?: Instant.fromEpochMilliseconds(0)
                            TestnetChallengePNLEntity.distributePoints(TestnetChallengePNLType.WeeklyPNL, intervalStart = lastWeeklyPointsRun, intervalEnd = lastUpdateInstant)
                            TestnetChallengePNLEntity.resetCurrentBalance(TestnetChallengePNLType.WeeklyPNL)
                            KeyValueStore.setInstant(weeklyPlnPointsKey, lastUpdateInstant)
                        }
                    }
                }

                TestnetChallengePNLEntity.updateAllBalances()
            }
        }
    }
}
