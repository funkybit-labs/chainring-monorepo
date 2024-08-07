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
    val amount = (decimal("amount", 30, 0))
    val isReplacement = bool("is_replacement")
    val balanceGuid = reference("balance_guid", BalanceTable).index()
}
