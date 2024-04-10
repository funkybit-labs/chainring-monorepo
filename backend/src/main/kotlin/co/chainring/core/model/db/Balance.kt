package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@JvmInline
value class BalanceId(override val value: String) : EntityId {
    companion object {
        fun generate(): BalanceId = BalanceId(TypeId.generate("balance").toString())
    }

    override fun toString(): String = value
}

data class BalanceUpdateAssignment(
    val walletId: WalletId,
    val symbolId: SymbolId,
    val delta: BigInteger,
    val balanceId: BalanceId,
    val balanceBefore: BigInteger?,
    val balanceAfter: BigInteger,
    val balanceType: BalanceType,
)

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
        fun updateBalances(assignments: List<BalanceUpdateAssignment>) {
            if (assignments.isEmpty()) {
                return
            }
            val now = Clock.System.now()
            val (updateAssignments, insertAssignments) = assignments.partition { it.balanceBefore != null }
            BalanceTable.batchInsert(insertAssignments) { assignment ->
                this[BalanceTable.guid] = assignment.balanceId
                this[BalanceTable.createdAt] = now
                this[BalanceTable.createdBy] = "system"
                this[BalanceTable.updatedAt] = now
                this[BalanceTable.updatedBy] = "system"
                this[BalanceTable.symbolGuid] = assignment.symbolId
                this[BalanceTable.balance] = assignment.balanceAfter.toBigDecimal()
                this[BalanceTable.walletGuid] = assignment.walletId
                this[BalanceTable.type] = assignment.balanceType
            }
            if (updateAssignments.isNotEmpty()) {
                BatchUpdateStatement(BalanceTable).apply {
                    updateAssignments.forEach { assignment ->
                        addBatch(EntityID(assignment.balanceId, BalanceTable))
                        this[BalanceTable.balance] = assignment.balanceAfter.toBigDecimal()
                        this[BalanceTable.updatedAt] = now
                        this[BalanceTable.updatedBy] = "system"
                    }
                    execute(TransactionManager.current())
                }
            }
            BalanceLogTable.batchInsert(assignments) { assignment ->
                this[BalanceLogTable.guid] = BalanceLogId.generate()
                this[BalanceLogTable.createdAt] = now
                this[BalanceLogTable.createdBy] = "system"
                this[BalanceLogTable.balanceGuid] = EntityID(assignment.balanceId, BalanceTable)
                this[BalanceLogTable.delta] = assignment.delta.toBigDecimal()
                this[BalanceLogTable.balanceBefore] = assignment.balanceBefore?.toBigDecimal() ?: BigDecimal.ZERO
                this[BalanceLogTable.balanceAfter] = assignment.balanceAfter.toBigDecimal()
            }
        }

        fun getBalances(walletIds: List<WalletId>, symbolIds: List<SymbolId>, balanceType: BalanceType): List<BalanceEntity> {
            return BalanceEntity.find {
                BalanceTable.walletGuid.inList(walletIds) and
                    BalanceTable.symbolGuid.inList(symbolIds) and
                    BalanceTable.type.eq(balanceType)
            }.toList()
        }

        fun getBalancesForWallet(walletEntity: WalletEntity): List<BalanceEntity> {
            return BalanceEntity.find {
                BalanceTable.walletGuid.eq(walletEntity.guid)
            }.toList()
        }
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
