package co.chainring.core.repeater.tasks

import co.chainring.core.model.db.KeyValueStore
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppUserRewardEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class DailyPointBonusTaskConfig(
    val referralBonusRate: BigDecimal = BigDecimal("0.1"),
    val threshold: BigDecimal = BigDecimal("0.001"),
)

private const val REFERRAL_POINTS_LAST_AWARDED = "referral_points_last_awarded"

class ReferralPointsTask(val config: DailyPointBonusTaskConfig = DailyPointBonusTaskConfig()) : RepeaterBaseTask(
    invokePeriod = 1.minutes,
    maxPlannedExecutionTime = 10.seconds,
) {
    private val logger = KotlinLogging.logger {}

    override fun runWithLock() {
        val now = Clock.System.now()
        transaction {
            val lastAwardedTimestamp = KeyValueStore.getInstant(REFERRAL_POINTS_LAST_AWARDED) ?: Instant.fromEpochMilliseconds(0)

            val pointsPerInviter = TelegramMiniAppUserRewardEntity.inviteePointsPerInviter(from = lastAwardedTimestamp, to = now)

            pointsPerInviter.forEach { (userId, inviteePoints) ->
                val invitor = TelegramMiniAppUserEntity[userId]
                val referralPoints = inviteePoints.multiply(config.referralBonusRate)

                if (inviteePoints >= config.threshold) {
                    TelegramMiniAppUserRewardEntity.referralBonus(invitor, referralPoints)
                }
            }

            logger.debug { "Granting referral points to ${pointsPerInviter.size} inviters" }

            KeyValueStore.setInstant(REFERRAL_POINTS_LAST_AWARDED, now)
        }
    }
}
