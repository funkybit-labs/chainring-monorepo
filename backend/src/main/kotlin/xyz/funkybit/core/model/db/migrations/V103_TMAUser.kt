package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.telegram.miniapp.TelegramMiniAppUserId
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.TestnetChallengeUtils

@Suppress("ClassName")
class V103_TMAUser : Migration() {
    object V103_UserTable : GUIDTable<UserId>("user", ::UserId)

    object V103_TelegramMiniAppUserTable : GUIDTable<TelegramMiniAppUserId>("telegram_mini_app_user", ::TelegramMiniAppUserId) {
        val userGuid = reference("user_guid", V103_UserTable).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                V103_TelegramMiniAppUserTable,
            )

            // add a user record for every existing tma user
            V103_TelegramMiniAppUserTable.select(V103_TelegramMiniAppUserTable.guid).map {
                val guid = it[V103_TelegramMiniAppUserTable.guid]
                val userId = UserId.generate()
                exec(
                    """
                    INSERT INTO "user" (guid, created_at, created_by, sequencer_id, invite_code, testnet_challenge_status)
                    VALUES ('$userId', now(), 'system', '${userId.toSequencerId().value}', '${TestnetChallengeUtils.inviteCode()}', 'Unenrolled')
                    """.trimIndent(),
                )
                exec(
                    """
                    UPDATE telegram_mini_app_user SET user_guid = '$userId' WHERE guid = '$guid'
                    """.trimIndent(),
                )
            }

            exec(
                """
                ALTER TABLE telegram_mini_app_user ALTER COLUMN user_guid SET NOT NULL
                """.trimIndent(),
            )
        }
    }
}
