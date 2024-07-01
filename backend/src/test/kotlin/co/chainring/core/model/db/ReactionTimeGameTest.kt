package co.chainring.core.model.db

import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGameReactionTime
import co.chainring.core.model.telegram.miniapp.TelegramMiniAppGameReactionTimeTable
import co.chainring.testutils.TestWithDb
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionTimeGameTest : TestWithDb() {

    @BeforeEach
    fun setup() {
        transaction {
            TelegramMiniAppGameReactionTimeTable.deleteAll()
        }
    }

    @Test
    fun `test reaction time recording increments count`() {
        transaction {
            assertEquals(0, TelegramMiniAppGameReactionTimeTable.selectAll().count())

            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100)

            assertEquals(2, TelegramMiniAppGameReactionTimeTable.selectAll().count())

            assertEquals(4, getCount(100L))
            assertEquals(1, getCount(200L))
            assertEquals(null, getCount(300L))
        }
    }

    @Test
    fun `test percentile calculation for first entry`() {
        transaction {
            assertEquals(100, TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100))
        }
    }

    @Test
    fun `test percentile calculation with multiple entries`() {
        transaction {
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(100)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(300)

            assertEquals(66, TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(190))
            assertEquals(0, TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(500))
            assertEquals(100, TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(50))
            assertEquals(33, TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(250))
        }
    }

    @Test
    fun `test percentile calculation with identical reaction times`() {
        transaction {
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)
            TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)

            val percentile = TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(200)
            // All reactions are the same, so reaction is not faster
            assertEquals(0, percentile)
        }
    }

    @Test
    fun `test calculate reaction take less that 10ms with full lookup table`() {
        transaction {
            (0..5000).forEach {
                TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(it.toLong())
            }
        }

        measureTimeMillis {
            transaction {
                TelegramMiniAppGameReactionTime.recordAndCalculatePercentile(2500)
            }
        }.also { time ->
            assertTrue { time <= 10 }
        }
    }

    private fun getCount(timeMs: Long) = TelegramMiniAppGameReactionTimeTable
        .select(TelegramMiniAppGameReactionTimeTable.count)
        .where { TelegramMiniAppGameReactionTimeTable.reactionTimeMs eq timeMs }
        .firstOrNull()?.get(TelegramMiniAppGameReactionTimeTable.count)
}
