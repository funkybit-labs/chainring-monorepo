package xyz.funkybit.core.model.db.migrations

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletAuthorizationId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V77_User : Migration() {

    object V77_UserTable : GUIDTable<UserId>("user", ::UserId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
    }

    @Serializable
    enum class V77_WalletFamily {
        Bitcoin,
        Evm,
    }

    object V77_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val walletFamily = customEnumeration(
            "wallet_family",
            "WalletFamily",
            { value -> V77_WalletFamily.valueOf(value as String) },
            { PGEnum("WalletFamily", it) },
        ).nullable()
        val userGuid = reference("user_guid", V77_UserTable).index().nullable()

        init {
            uniqueIndex(
                customIndexName = "wallet_family_user",
                columns = arrayOf(userGuid, walletFamily),
            )
        }
    }

    object V77_WalletAuthorizationTable : GUIDTable<WalletAuthorizationId>("wallet_authorization", ::WalletAuthorizationId) {
        val walletGuid = reference("wallet_guid", V77_WalletTable, onDelete = ReferenceOption.CASCADE).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val message = varchar("message", 10485760)
        val signature = varchar("signature", 10485760)
    }

    object V77_LimitTable : IntIdTable("limit") {
        val userGuid = reference("user_guid", V77_UserTable).index().nullable()
    }

    override fun run() {
        transaction {
            // add user table
            SchemaUtils.createMissingTablesAndColumns(V77_UserTable)

            // add wallet_family and user to wallet
            exec("CREATE TYPE WalletFamily AS ENUM (${enumDeclaration<V77_WalletFamily>()})")
            SchemaUtils.createMissingTablesAndColumns(V77_WalletTable)
            V77_WalletTable.selectAll().forEach { walletRecord ->
                val userId = UserId.generate()
                V77_UserTable.insert { userRecord ->
                    userRecord[V77_UserTable.guid] = userId
                    userRecord[V77_UserTable.createdAt] = Clock.System.now()
                    userRecord[V77_UserTable.createdBy] = "V77_WalletTable"
                }
                V77_WalletTable.update({V77_WalletTable.guid eq walletRecord[V77_WalletTable.guid]}) {
                    it[userGuid] = userId
                    it[walletFamily] = V77_WalletFamily.Evm
                }
            }
            exec("ALTER TABLE wallet ALTER COLUMN user_guid SET NOT NULL")
            exec("ALTER TABLE wallet ALTER COLUMN wallet_family SET NOT NULL")

            // link proof table
            SchemaUtils.createMissingTablesAndColumns(V77_WalletAuthorizationTable)

            // change wallet_guid in limit table to user_guid
            exec("ALTER TABLE \"limit\" DROP CONSTRAINT unique_limit")
            SchemaUtils.createMissingTablesAndColumns(V77_LimitTable)
            exec("UPDATE \"limit\" SET user_guid = (SELECT user_guid FROM wallet WHERE wallet.guid = \"limit\".wallet_guid)")
            exec("ALTER TABLE \"limit\" DROP COLUMN wallet_guid")
            exec("ALTER TABLE \"limit\" ALTER COLUMN user_guid SET NOT NULL")
            exec("ALTER TABLE \"limit\" ADD CONSTRAINT unique_limit UNIQUE(market_guid, user_guid)")
        }
    }
}
