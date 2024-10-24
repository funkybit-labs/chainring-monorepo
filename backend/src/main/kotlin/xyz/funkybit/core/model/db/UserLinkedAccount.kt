package xyz.funkybit.core.model.db

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.UpsertStatement
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.upsert
import xyz.funkybit.core.utils.OAuth2

@Serializable
enum class UserLinkedAccountType {
    Discord,
    X,
}

object UserAccountLinkingIntentTable : IntIdTable("user_account_linking_intent") {
    val userGuid = reference("user_guid", UserTable)
    val type = customEnumeration(
        "type",
        "UserLinkedAccountType",
        { value -> UserLinkedAccountType.valueOf(value as String) },
        { PGEnum("UserLinkedAccountType", it) },
    )
    val oauth2CodeVerifier = varchar("oauth2_code_verifier", 10485760).nullable()
    val createdAt = timestamp("created_at")

    init {
        index(true, userGuid, type)
    }
}

class UserAccountLinkingIntentEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserAccountLinkingIntentEntity>(UserAccountLinkingIntentTable) {
        fun create(user: UserEntity, accountType: UserLinkedAccountType) {
            UserAccountLinkingIntentTable.upsert(
                keys = arrayOf(UserAccountLinkingIntentTable.userGuid, UserAccountLinkingIntentTable.type),
                onUpdate = listOfNotNull(
                    if (accountType == UserLinkedAccountType.X) {
                        UserAccountLinkingIntentTable.oauth2CodeVerifier to stringLiteral(OAuth2.CodeVerifier.generate().value)
                    } else {
                        null
                    },
                    UserAccountLinkingIntentTable.createdAt to stringLiteral("now()"),
                ),
                body = fun UserAccountLinkingIntentTable.(it: UpsertStatement<Long>) {
                    it[this.userGuid] = user.guid
                    it[this.type] = accountType
                    if (accountType == UserLinkedAccountType.X) {
                        it[this.oauth2CodeVerifier] = OAuth2.CodeVerifier.generate().value
                    }
                    it[this.createdAt] = Clock.System.now()
                },
            )
        }

        fun getForUser(user: UserEntity, accountType: UserLinkedAccountType): UserAccountLinkingIntentEntity =
            findForUser(user, accountType)!!

        fun findForUser(user: UserEntity, accountType: UserLinkedAccountType): UserAccountLinkingIntentEntity? =
            UserAccountLinkingIntentEntity
                .find { UserAccountLinkingIntentTable.userGuid.eq(user.guid).and(UserAccountLinkingIntentTable.type.eq(accountType)) }
                .firstOrNull()
    }

    var userGuid by UserAccountLinkingIntentTable.userGuid
    var type by UserAccountLinkingIntentTable.type
    var oauth2CodeVerifier by UserAccountLinkingIntentTable.oauth2CodeVerifier.transform(
        toColumn = { it?.value },
        toReal = { str -> str?.let { OAuth2.CodeVerifier(it) } },
    )
    var createdAt by UserAccountLinkingIntentTable.createdAt
}

object UserLinkedAccountTable : IntIdTable("user_linked_account") {
    val userGuid = reference("user_guid", UserTable)
    val type = customEnumeration(
        "type",
        "UserLinkedAccountType",
        { value -> UserLinkedAccountType.valueOf(value as String) },
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

class UserLinkedAccountEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserLinkedAccountEntity>(UserLinkedAccountTable) {
        fun create(user: UserEntity, accountType: UserLinkedAccountType, accountId: String, oauth2Tokens: OAuth2.Tokens): UserLinkedAccountEntity =
            UserLinkedAccountEntity.new {
                this.userGuid = user.guid
                this.type = accountType
                this.accountId = accountId
                this.oauth2AccessToken = oauth2Tokens.access
                this.oauth2RefreshToken = oauth2Tokens.refresh
                this.createdAt = Clock.System.now()
            }
    }

    var userGuid by UserLinkedAccountTable.userGuid
    var type by UserLinkedAccountTable.type
    var accountId by UserLinkedAccountTable.accountId
    var oauth2AccessToken by UserLinkedAccountTable.oauth2AccessToken
    var oauth2RefreshToken by UserLinkedAccountTable.oauth2RefreshToken
    var createdAt by UserLinkedAccountTable.createdAt
}
