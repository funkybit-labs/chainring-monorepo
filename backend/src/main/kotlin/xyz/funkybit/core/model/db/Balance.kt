package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import xyz.funkybit.apps.api.model.Balance
import xyz.funkybit.apps.api.model.BalancesApiResponse
import xyz.funkybit.core.model.Symbol
import java.math.BigInteger

@Serializable
@JvmInline
value class BalanceId(override val value: String) : EntityId {
    companion object {
        fun generate(): BalanceId = BalanceId(TypeId.generate("balance").toString())
    }

    override fun toString(): String = value
}

sealed class BalanceChange {
    abstract val walletId: WalletId
    abstract val symbolId: SymbolId

    data class Delta(
        override val walletId: WalletId,
        override val symbolId: SymbolId,
        val amount: BigInteger,
    ) : BalanceChange()

    data class Replace(
        override val walletId: WalletId,
        override val symbolId: SymbolId,
        val amount: BigInteger,
    ) : BalanceChange()
}

enum class BalanceType {
    Exchange,
    Available,
}

object BalanceTable : GUIDTable<BalanceId>("balance", ::BalanceId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val symbolGuid = reference("symbol_guid", SymbolTable).index()
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val balance = decimal("balance", 30, 0)
    val type = BalanceTable.customEnumeration(
        "type",
        "BalanceType",
        { value -> BalanceType.valueOf(value as String) },
        { PGEnum("BalanceType", it) },
    )
}

class BalanceEntity(guid: EntityID<BalanceId>) : GUIDEntity<BalanceId>(guid) {
    companion object : EntityClass<BalanceId, BalanceEntity>(BalanceTable) {
        fun updateBalances(changes: List<BalanceChange>, balanceType: BalanceType) {
            val startingBalances = getBalances(
                changes.map { it.walletId }.toSet().toList(),
                changes.map { it.symbolId }.toSet().toList(),
                balanceType,
            )
            updateBalances(
                changes.map { change ->
                    val startingBalanceEntity = startingBalances.firstOrNull { it.wallet.guid.value == change.walletId && it.symbol.guid.value == change.symbolId }
                    BalanceUpdateAssignment(
                        id = startingBalanceEntity?.guid ?: EntityID(BalanceId.generate(), BalanceTable),
                        walletId = change.walletId,
                        symbolId = change.symbolId,
                        value = when (change) {
                            is BalanceChange.Delta -> change.amount
                            is BalanceChange.Replace -> change.amount
                        },
                        balanceBefore = startingBalanceEntity != null,
                        balanceType = balanceType,
                        changeType = change,
                    )
                },
            )
        }

        fun balancesAsApiResponse(walletEntity: WalletEntity): BalancesApiResponse {
            val (availableBalances, exchangeBalances) = getBalancesForWallet(
                walletEntity,
            ).map {
                it.refresh(flush = true)
                it
            }.partition { it.type == BalanceType.Available }
            val exchangeBalanceMap = exchangeBalances.associate { it.symbolGuid.value to it.balance }

            return BalancesApiResponse(
                availableBalances.map { availableBalance ->
                    Balance(
                        symbol = Symbol(availableBalance.symbol.name),
                        total = exchangeBalanceMap.getOrDefault(availableBalance.symbol.guid.value, BigInteger.ZERO),
                        available = availableBalance.balance,
                        lastUpdated = availableBalance.updatedAt ?: availableBalance.createdAt,
                    )
                },
            )
        }

        private data class BalanceUpdateAssignment(
            val id: EntityID<BalanceId>,
            val walletId: WalletId,
            val symbolId: SymbolId,
            val value: BigInteger,
            val balanceBefore: Boolean,
            val balanceType: BalanceType,
            val changeType: BalanceChange,
        )

        private fun updateBalances(assignments: List<BalanceUpdateAssignment>) {
            if (assignments.isEmpty()) {
                return
            }
            val now = Clock.System.now()
            val (updateAssignments, insertAssignments) = assignments.partition { it.balanceBefore }
            BalanceTable.batchInsert(insertAssignments) { assignment ->
                this[BalanceTable.guid] = assignment.id
                this[BalanceTable.createdAt] = now
                this[BalanceTable.createdBy] = "system"
                this[BalanceTable.updatedAt] = now
                this[BalanceTable.updatedBy] = "system"
                this[BalanceTable.symbolGuid] = assignment.symbolId
                this[BalanceTable.balance] = assignment.value.toBigDecimal()
                this[BalanceTable.walletGuid] = assignment.walletId
                this[BalanceTable.type] = assignment.balanceType
            }
            if (updateAssignments.isNotEmpty()) {
                val (deltaUpdateAssignments, replaceUpdateAssignments) = updateAssignments.partition { it.changeType is BalanceChange.Delta }
                if (deltaUpdateAssignments.isNotEmpty()) {
                    TransactionManager.current().exec(
                        """
                    UPDATE ${BalanceTable.nameInDatabaseCase()}
                       SET ${BalanceTable.balance.nameInDatabaseCase()} = ${BalanceTable.balance.nameInDatabaseCase()} + data_table.value
                      FROM (
                        SELECT unnest(?) as guid, unnest(?) as value
                      ) AS data_table
                     WHERE ${BalanceTable.nameInDatabaseCase()}.${BalanceTable.guid.nameInDatabaseCase()} = data_table.guid
                        """.trimIndent(),
                        listOf(
                            ArrayColumnType(BalanceTable.guid.columnType) to deltaUpdateAssignments.map { it.id }
                                .toTypedArray(),
                            ArrayColumnType(BalanceTable.balance.columnType) to deltaUpdateAssignments.map { it.value }
                                .toTypedArray(),
                        ),
                    )
                }
                if (replaceUpdateAssignments.isNotEmpty()) {
                    BatchUpdateStatement(BalanceTable).apply {
                        replaceUpdateAssignments.forEach { assignment ->
                            addBatch(assignment.id)
                            this[BalanceTable.balance] = assignment.value.toBigDecimal()
                            this[BalanceTable.updatedAt] = now
                            this[BalanceTable.updatedBy] = "system"
                        }
                        execute(TransactionManager.current())
                    }
                }
            }
            BalanceLogTable.batchInsert(assignments) { assignment ->
                this[BalanceLogTable.guid] = BalanceLogId.generate()
                this[BalanceLogTable.createdAt] = now
                this[BalanceLogTable.createdBy] = "system"
                this[BalanceLogTable.balanceGuid] = assignment.id
                this[BalanceLogTable.amount] = assignment.value.toBigDecimal()
                this[BalanceLogTable.isReplacement] = assignment.changeType is BalanceChange.Replace
            }
        }

        fun getBalances(walletIds: List<WalletId>, symbolIds: List<SymbolId>, type: BalanceType): List<BalanceEntity> {
            return BalanceEntity.find {
                BalanceTable.walletGuid.inList(walletIds) and
                    BalanceTable.symbolGuid.inList(symbolIds) and
                    BalanceTable.type.eq(type)
            }.with(BalanceEntity::symbol).toList()
        }

        fun getBalancesForWallet(wallet: WalletEntity): List<BalanceEntity> =
            BalanceEntity.find {
                BalanceTable.walletGuid.eq(wallet.guid)
            }.with(BalanceEntity::symbol).toList()

        fun findForWalletAndSymbol(wallet: WalletEntity, symbol: SymbolEntity, type: BalanceType): BalanceEntity? =
            BalanceEntity.find {
                BalanceTable.walletGuid.eq(wallet.guid)
                    .and(BalanceTable.symbolGuid.eq(symbol.guid))
                    .and(BalanceTable.type.eq(type))
            }.singleOrNull()
    }

    var createdAt by BalanceTable.createdAt
    var createdBy by BalanceTable.createdBy
    var symbolGuid by BalanceTable.symbolGuid
    var symbol by SymbolEntity referencedOn BalanceTable.symbolGuid
    var walletGuid by BalanceTable.walletGuid
    var wallet by WalletEntity referencedOn BalanceTable.walletGuid
    var balance by BalanceTable.balance.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var updatedAt by BalanceTable.updatedAt
    var updatedBy by BalanceTable.updatedBy
    var type by BalanceTable.type
}
