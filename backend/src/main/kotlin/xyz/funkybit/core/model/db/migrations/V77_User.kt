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
    enum class V77_WalletType {
        Bitcoin,
        Evm,
    }

    object V77_WalletTable : GUIDTable<WalletId>("wallet", ::WalletId) {
        val type = customEnumeration(
            "type",
            "WalletType",
            { value -> V77_WalletType.valueOf(value as String) },
            { PGEnum("WalletType", it) },
        ).index()
        val userGuid = reference("user_guid", V77_UserTable).index()

        init {
            uniqueIndex(
                customIndexName = "wallet_type_user",
                columns = arrayOf(userGuid, type),
            )
        }
    }

    object V77_LimitTable : IntIdTable("limit") {
        val userGuid = reference("user_guid", V77_UserTable).index().nullable()
    }

    override fun run() {
        transaction {
            // TODO add backward compatibility

            SchemaUtils.createMissingTablesAndColumns(V77_UserTable)

            exec("CREATE TYPE WalletType AS ENUM (${enumDeclaration<V77_WalletType>()})")
            SchemaUtils.createMissingTablesAndColumns(V77_WalletTable)

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
