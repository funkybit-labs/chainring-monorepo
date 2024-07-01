package co.chainring.core.model.telegram.miniapp

import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.UpsertStatement
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.upsert
import kotlin.math.round

object TelegramMiniAppGameReactionTimeTable : Table("telegram_mini_app_game_reaction_time") {
    val reactionTimeMs = long("reaction_time_ms")
    val count = long("count")

    override val primaryKey = PrimaryKey(reactionTimeMs)
}

object TelegramMiniAppGameReactionTime {
    private const val STEP_SIZE_MS = 10L

    fun recordAndCalculatePercentile(timeMs: Long): Int {
        // round timeMs to 10 ms to decrease number of records in the lookup table. 500 is enough to keep good precision.
        val roundedTimeMs = round(timeMs.toDouble() / STEP_SIZE_MS).toLong() * STEP_SIZE_MS

        // calculate percentile
        val totalReactions = TelegramMiniAppGameReactionTimeTable.select(
            TelegramMiniAppGameReactionTimeTable.count.sum(),
        ).firstOrNull()?.get(TelegramMiniAppGameReactionTimeTable.count.sum()) ?: 0L

        val fasterReactions = TelegramMiniAppGameReactionTimeTable.select(
            TelegramMiniAppGameReactionTimeTable.count.sum(),
        ).where {
            TelegramMiniAppGameReactionTimeTable.reactionTimeMs lessEq roundedTimeMs
        }.firstOrNull()?.get(TelegramMiniAppGameReactionTimeTable.count.sum()) ?: 0L

        val percentile = when {
            totalReactions > 0 -> (((totalReactions - fasterReactions).toDouble() / totalReactions) * 100).toInt()
            // single reaction is user's own, count it as if user was the fastest
            else -> 100
        }

        // record reaction time
        TelegramMiniAppGameReactionTimeTable.upsert(
            keys = arrayOf(TelegramMiniAppGameReactionTimeTable.reactionTimeMs),
            onUpdate = mutableListOf(
                TelegramMiniAppGameReactionTimeTable.count to TelegramMiniAppGameReactionTimeTable.count.plus(1L),
            ),
            body = fun TelegramMiniAppGameReactionTimeTable.(it: UpsertStatement<Long>) {
                it[reactionTimeMs] = roundedTimeMs
                it[count] = 1L
            },
        )

        return percentile
    }
}
