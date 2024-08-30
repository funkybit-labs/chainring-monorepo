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
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletId
import xyz.funkybit.core.model.db.WalletLinkedProofId
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
    enum class V77_WalletType {
        Bitcoin,
        Evm,
    }

    object V77_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val address = varchar("address", 10485760).uniqueIndex()
        val type = customEnumeration(
            "type",
            "WalletType",
            { value -> V77_WalletType.valueOf(value as String) },
            { PGEnum("WalletType", it) },
        ).nullable()
        val userGuid = reference("user_guid", V77_UserTable).index().nullable()

        init {
            uniqueIndex(
                customIndexName = "wallet_type_user",
                columns = arrayOf(userGuid, type),
            )
        }
    }

    object V77_WalletLinkProofTable : GUIDTable<WalletLinkedProofId>("wallet_link_proof", ::WalletLinkedProofId) {
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

            // add type and user to wallet
            exec("CREATE TYPE WalletType AS ENUM (${enumDeclaration<V77_WalletType>()})")
            SchemaUtils.createMissingTablesAndColumns(V77_WalletTable)
            V77_WalletTable.selectAll().forEach { walletRecord ->
                val userId = UserId.generate()
                V77_UserTable.insert { userRecord ->
                    userRecord[V77_UserTable.guid] = userId
                    userRecord[V77_UserTable.createdAt] = Clock.System.now()
                    userRecord[V77_UserTable.createdBy] = "V77_WalletTable"
                }
                walletRecord[V77_WalletTable.userGuid] = userId
                walletRecord[V77_WalletTable.type] = V77_WalletType.Evm
            }
            exec("ALTER TABLE wallet ALTER COLUMN user_guid SET NOT NULL")
            exec("ALTER TABLE wallet ALTER COLUMN type SET NOT NULL")

            // link proof table
            SchemaUtils.createMissingTablesAndColumns(V77_WalletLinkProofTable)

            // change wallet_guid in limit table to user_guid
            exec("ALTER TABLE \"limit\" DROP CONSTRAINT unique_limit")
            SchemaUtils.createMissingTablesAndColumns(V77_LimitTable)
            exec("UPDATE \"limit\" SET user_guid = (SELECT user_guid FROM wallet WHERE wallet.user_guid = \"limit\".user_guid)")
            exec("ALTER TABLE \"limit\" DROP COLUMN wallet_guid")
            exec("ALTER TABLE \"limit\" ALTER COLUMN user_guid SET NOT NULL")
            exec("ALTER TABLE \"limit\" ADD CONSTRAINT unique_limit UNIQUE(market_guid, user_guid)")
        }
    }
}
