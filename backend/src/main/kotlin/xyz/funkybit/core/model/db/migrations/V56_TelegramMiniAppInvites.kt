package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppInviteCode
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId
import kotlin.random.Random

@Suppress("ClassName")
class V56_TelegramMiniAppInvites : Migration() {
    object V56_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val inviteCode = varchar("invite_code", 10485760).uniqueIndex().nullable()
        val invitedBy = reference("invited_by", V56_TelegramMiniAppUserTable).index().nullable()
    }

    class V56_TelegramMiniAppInviteCode(val value: String) {
        companion object {
            private const val CODE_LENGTH = 9
            private val CHAR_POOL: List<Char> = ('A'..'Z') + ('0'..'9')

            fun generate(): TelegramMiniAppInviteCode {
                val code = (1..CODE_LENGTH)
                    .map { Random.nextInt(0, CHAR_POOL.size) }
                    .map(CHAR_POOL::get)
                    .joinToString("")

                return TelegramMiniAppInviteCode(code)
            }
        }
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V56_TelegramMiniAppUserTable)

            // backfill inviteCodes
            V56_TelegramMiniAppUserTable.selectAll().forEach { row ->
                val newInviteCode = V56_TelegramMiniAppInviteCode.generate()
                V56_TelegramMiniAppUserTable.update({ V56_TelegramMiniAppUserTable.id eq row[V56_TelegramMiniAppUserTable.id] }) {
                    it[inviteCode] = newInviteCode.value
                }
            }
            exec("ALTER TABLE telegram_mini_app_user ALTER COLUMN invite_code SET NOT NULL ")

            // add index on created_at for referral points calculation
            exec("ALTER TABLE telegram_mini_app_user_reward DROP COLUMN updated_at")
            exec("CREATE INDEX telegram_mini_app_user_reward_created_at_index ON telegram_mini_app_user_reward (created_at)")
        }
    }
}
