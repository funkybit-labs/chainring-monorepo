package xyz.funkybit.core.repeater.tasks

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.KeyValueStore
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardEntity
import xyz.funkybit.core.utils.TestnetChallengeUtils
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TestnetChallengeReferralPointsTask : RepeaterBaseTask(
    invokePeriod = 1.hours,
    maxPlannedExecutionTime = 1.minutes,
) {
    override val name: String = "testnet_challenge_referral_points"

    private val referralPointsKey: String = "referral_points_granted_at"

    override fun runWithLock() {
        if (TestnetChallengeUtils.enabled) {
            transaction {
                val lastGrantedAt = KeyValueStore.getInstant(referralPointsKey) ?: Instant.fromEpochMilliseconds(0)
                val now = Clock.System.now()

                TestnetChallengeUserRewardEntity.distributeReferralPoints(
                    earnedAfter = lastGrantedAt,
                    earnedBefore = now,
                    referralBonusSize = BigDecimal("0.1"),
                    referralBonusMinAmount = BigDecimal("0.01"),
                )

                KeyValueStore.setInstant(referralPointsKey, now)
            }
        }
    }
}
