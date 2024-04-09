package co.chainring.core.model.db

import de.fxlae.typeid.TypeId
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@JvmInline
value class BalanceLogId(override val value: String) : EntityId {
    companion object {
        fun generate(): BalanceLogId = BalanceLogId(TypeId.generate("ballog").toString())
    }

    override fun toString(): String = value
}

object BalanceLogTable : GUIDTable<BalanceLogId>("balance_log", ::BalanceLogId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val balanceBefore = (decimal("balance_before", 30, 0))
    val balanceAfter = (decimal("balance_after", 30, 0))
    val delta = (decimal("delta", 30, 0))
    val balanceGuid = reference("balance_guid", BalanceTable)
}
