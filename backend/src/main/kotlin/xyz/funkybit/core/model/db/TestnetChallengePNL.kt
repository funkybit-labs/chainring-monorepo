package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.core.utils.TestnetChallengeUtils

@Serializable
@JvmInline
value class TestnetChallengePNLId(override val value: String) : EntityId {
    companion object {
        fun generate(): TestnetChallengePNLId = TestnetChallengePNLId(TypeId.generate("tncpnl").toString())
    }

    override fun toString(): String = value
}

enum class TestnetChallengePNLType {
    DailyPNL,
    WeeklyPNL,
    OverallPNL,
}

object TestnetChallengePNLTable : GUIDTable<TestnetChallengePNLId>("testnet_challenge_pnl", ::TestnetChallengePNLId) {
    val asOf = timestamp("as_of").index()
    val userGuid = reference("user_guid", UserTable)
    val initialBalance = decimal("initial_balance", 30, 18)
    val currentBalance = decimal("current_balance", 30, 18)
    val type = customEnumeration(
        "type",
        "TestnetChallengePNLType",
        { value -> TestnetChallengePNLType.valueOf(value as String) },
        { PGEnum("TestnetChallengePNLType", it) },
    )

    init {
        uniqueIndex(
            customIndexName = "uix_testnet_challenge_pnl_type_user_guid",
            columns = arrayOf(type, userGuid),
        )
    }
}

class TestnetChallengePNLEntity(guid: EntityID<TestnetChallengePNLId>) : GUIDEntity<TestnetChallengePNLId>(guid) {
    companion object : EntityClass<TestnetChallengePNLId, TestnetChallengePNLEntity>(TestnetChallengePNLTable) {
        fun initializeForUser(user: UserEntity) {
            val now = Clock.System.now()
            TestnetChallengePNLType.entries.forEach { type ->
                TestnetChallengePNLTable.insert {
                    it[guid] = TestnetChallengePNLId.generate()
                    it[asOf] = now
                    it[userGuid] = user.guid
                    it[initialBalance] = TestnetChallengeUtils.depositAmount
                    it[currentBalance] = TestnetChallengeUtils.depositAmount
                    it[TestnetChallengePNLTable.type] = type
                }
            }
        }

        fun updateAllBalances() {
            val referenceSymbol = TestnetChallengeUtils.depositSymbol()
            TransactionManager.current().exec(
                """
                WITH balance_summary AS (
                    SELECT 
                        u.${UserTable.guid.name} AS user_guid,
                        SUM(bal.${BalanceTable.balance.name} * COALESCE(mkt.${MarketTable.feedPrice.name}, case when bal.${BalanceTable.symbolGuid.name} = '${referenceSymbol.guid.value}' then 1.0 else 0.0 end)) AS total_balance
                    FROM 
                        ${BalanceTable.tableName} AS bal
                    INNER JOIN 
                        ${WalletTable.tableName} AS w ON bal.${BalanceTable.walletGuid.name} = w.${WalletTable.guid.name}
                    INNER JOIN 
                        "${UserTable.tableName}" AS u ON w.user_guid = u.${UserTable.guid.name}
                    LEFT JOIN 
                        ${MarketTable.tableName} AS mkt ON
                          mkt.${MarketTable.baseSymbolGuid.name} = bal.${BalanceTable.symbolGuid.name} AND
                          mkt.${MarketTable.quoteSymbolGuid.name} = '${referenceSymbol.guid.value}'
                    WHERE ${BalanceTable.type.name} = '${BalanceType.Exchange}'
                    GROUP BY 
                        u.${UserTable.guid.name}
                )
                UPDATE ${TestnetChallengePNLTable.tableName} AS tcp
                SET 
                    ${TestnetChallengePNLTable.currentBalance.name} = bs.total_balance,
                    as_of = now()
                FROM 
                    balance_summary AS bs
                WHERE 
                    tcp.${TestnetChallengePNLTable.userGuid.name} = bs.user_guid;
                """.trimIndent(),
            )
        }
    }

    var asOf by TestnetChallengePNLTable.asOf
    var userGuid by TestnetChallengePNLTable.userGuid
    var user by UserEntity referencedOn TestnetChallengePNLTable.userGuid
    var initialBalance by TestnetChallengePNLTable.initialBalance
    var currentBalance by TestnetChallengePNLTable.currentBalance
    val type by TestnetChallengePNLTable.type
}
