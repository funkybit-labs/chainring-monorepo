package xyz.funkybit.core.model.db

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.telegram.TelegramUserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGameReactionTime
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppGameReactionTimeTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserEntity
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserIsBot
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserRewardType
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserTable
import xyz.funkybit.core.model.telegram.miniapp.ofType
import xyz.funkybit.testutils.TestWithDb
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionTimeGameTest : TestWithDb() {

    @BeforeEach
    fun setup() {
        transaction {
            TelegramMiniAppGameReactionTimeTable.deleteAll()
            TelegramMiniAppUserRewardTable.deleteAll()
            TelegramMiniAppUserTable.deleteAll()
        }
    }

    @Test
    fun `test reaction time recording increments count`() {
        transaction {
            assertEquals(0, TelegramMiniAppGameReactionTimeTable.selectAll().count())

            recordAndCalculatePercentile(100)
            recordAndCalculatePercentile(100)
            recordAndCalculatePercentile(200)
            recordAndCalculatePercentile(100)
            recordAndCalculatePercentile(100)

            assertEquals(2, TelegramMiniAppGameReactionTimeTable.selectAll().count())

            assertEquals(4, getCount(100L))
            assertEquals(1, getCount(200L))
            assertEquals(null, getCount(300L))
        }
    }

    @Test
    fun `test percentile calculation for first entry`() {
        transaction {
            assertEquals(100, recordAndCalculatePercentile(100))
        }
    }

    @Test
    fun `test percentile calculation with multiple entries`() {
        transaction {
            recordAndCalculatePercentile(100)
            recordAndCalculatePercentile(200)
            recordAndCalculatePercentile(300)

            assertEquals(66, recordAndCalculatePercentile(190))
            assertEquals(0, recordAndCalculatePercentile(500))
            assertEquals(100, recordAndCalculatePercentile(50))
            assertEquals(33, recordAndCalculatePercentile(250))
        }
    }

    @Test
    fun `test percentile calculation with identical reaction times`() {
        transaction {
            recordAndCalculatePercentile(200)
            recordAndCalculatePercentile(200)
            recordAndCalculatePercentile(200)

            val percentile = recordAndCalculatePercentile(200)
            // All reactions are the same, so reaction is not faster
            assertEquals(0, percentile)
        }
    }

    @Test
    fun `test calculate reaction take less that 10ms with full lookup table`() {
        transaction {
            (0..5000).forEach {
                recordAndCalculatePercentile(it.toLong())
            }
        }

        measureTimeMillis {
            transaction {
                recordAndCalculatePercentile(2500)
            }
        }.also { time ->
            assertTrue { time <= 10 }
        }
    }

    @Test
    fun `bot detection`() {
        transaction {
            val user = TelegramMiniAppUserEntity.create(TelegramUserId(123), invitedBy = null)
            user.gameTickets = 10
            user.flush()
            assertEquals(TelegramMiniAppUserIsBot.No, user.isBot)

            user.useGameTicket(reactionTimeMs = 100)
            assertEquals(1, getCount(100))

            user.useGameTicket(reactionTimeMs = 20)
            assertEquals(TelegramMiniAppUserIsBot.Maybe, user.isBot)

            user.useGameTicket(reactionTimeMs = 30)
            assertEquals(TelegramMiniAppUserIsBot.No, user.isBot)

            user.useGameTicket(reactionTimeMs = 20)
            assertEquals(TelegramMiniAppUserIsBot.Maybe, user.isBot)

            user.useGameTicket(reactionTimeMs = 20)
            assertEquals(TelegramMiniAppUserIsBot.Yes, user.isBot)

            user.flush()

            val prevReactionGamePoints = user.pointsBalances().ofType(TelegramMiniAppUserRewardType.ReactionGame)

            // check that bot reaction times do not affect percentiles table
            // but user still gets rewards
            user.useGameTicket(reactionTimeMs = 50)
            user.flush()

            assertEquals(null, getCount(50))
            assertTrue(user.pointsBalances().ofType(TelegramMiniAppUserRewardType.ReactionGame) > prevReactionGamePoints)
        }
    }

    private fun recordAndCalculatePercentile(timeMs: Long): Int {
        val roundedTime = TelegramMiniAppGameReactionTime.roundTime(timeMs)
        val percentile = TelegramMiniAppGameReactionTime.calculatePercentile(roundedTime)
        TelegramMiniAppGameReactionTime.record(roundedTime)
        return percentile
    }

    private fun getCount(timeMs: Long) = TelegramMiniAppGameReactionTimeTable
        .select(TelegramMiniAppGameReactionTimeTable.count)
        .where { TelegramMiniAppGameReactionTimeTable.reactionTimeMs eq timeMs }
        .firstOrNull()?.get(TelegramMiniAppGameReactionTimeTable.count)
}
