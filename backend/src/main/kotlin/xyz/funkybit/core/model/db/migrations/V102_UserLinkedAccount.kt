package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V102_UserLinkedAccount : Migration() {
    object V102_UserTable : GUIDTable<UserId>("user", ::UserId)

    @Serializable
    enum class V102_UserLinkedAccountType {
        Discord,
        X,
    }

    object V102_UserAccountLinkingIntentTable : IntIdTable("user_account_linking_intent") {
        val userGuid = reference("user_guid", V102_UserTable)
        val type = customEnumeration(
            "type",
            "UserLinkedAccountType",
            { value -> V102_UserLinkedAccountType.valueOf(value as String) },
            { PGEnum("UserLinkedAccountType", it) },
        )
        val oauth2CodeVerifier = varchar("oauth2_code_verifier", 10485760).nullable()
        val createdAt = timestamp("created_at")

        init {
            index(true, userGuid, type)
        }
    }

    object V102_UserLinkedAccountTable : IntIdTable("user_linked_account") {
        val userGuid = reference("user_guid", V102_UserTable)
        val type = customEnumeration(
            "type",
            "UserLinkedAccountType",
            { value -> V102_UserLinkedAccountType.valueOf(value as String) },
            { PGEnum("UserLinkedAccountType", it) },
        )
        val accountId = varchar("account_id", 10485760)
        val oauth2AccessToken = varchar("oauth2_access_token", 10485760)
        val oauth2RefreshToken = varchar("oauth2_refresh_token", 10485760)
        val createdAt = timestamp("created_at")

        init {
            index(true, userGuid, type)
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE UserLinkedAccountType AS ENUM (${enumDeclaration<V102_UserLinkedAccountType>()})")

            SchemaUtils.createMissingTablesAndColumns(
                V102_UserAccountLinkingIntentTable,
                V102_UserLinkedAccountTable,
            )

            exec("ALTER TABLE \"user\" DROP COLUMN discord_user_id")
            exec("ALTER TABLE \"user\" DROP COLUMN discord_access_token")
            exec("ALTER TABLE \"user\" DROP COLUMN discord_refresh_token")
        }
    }
}
