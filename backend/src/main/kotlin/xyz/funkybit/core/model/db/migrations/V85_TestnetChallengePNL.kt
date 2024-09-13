package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.TestnetChallengePNLId
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V85_TestnetChallengePNL : Migration() {

    enum class V85_TestnetChallengePNLType {
        DailyPNL,
        WeeklyPNL,
        OverallPNL,
    }
    object V85_UserTable : GUIDTable<UserId>("user", ::UserId)
    object V85_TestnetChallengePNLTable : GUIDTable<TestnetChallengePNLId>("testnet_challenge_pnl", ::TestnetChallengePNLId) {
        val asOf = timestamp("as_of").index()
        val userGuid = reference("user_guid", V85_UserTable)
        val initialBalance = decimal("initial_balance", 30, 18)
        val currentBalance = decimal("current_balance", 30, 18)
        val type = customEnumeration(
            "type",
            "TestnetChallengePNLType",
            { value -> V85_TestnetChallengePNLType.valueOf(value as String) },
            { PGEnum("TestnetChallengePNLType", it) },
        )

        init {
            uniqueIndex(
                customIndexName = "uix_testnet_challenge_pnl_type_user_guid",
                columns = arrayOf(type, userGuid),
            )
        }
    }
    override fun run() {
        transaction {
            exec("CREATE TYPE TestnetChallengePNLType AS ENUM (${enumDeclaration<V85_TestnetChallengePNLType>()})")
            SchemaUtils.createMissingTablesAndColumns(V85_TestnetChallengePNLTable)
        }
    }
}
