package co.chainring.core.model.db

import co.chainring.core.model.Symbol
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.math.BigInteger

@Serializable
@JvmInline
value class ChainId(val value: ULong) : Comparable<ChainId> {
    constructor(bigInt: BigInteger) : this(bigInt.toLong().toULong())
    override fun toString(): String = value.toString()
    fun toLong(): Long = value.toLong()
    override fun compareTo(other: ChainId): Int = compareValuesBy(this, other) { it.value }
}

class ChainIdColumnType : ColumnType() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.uintegerType()
    override fun valueFromDB(value: Any): ChainId {
        return when (value) {
            is ULong -> ChainId(value)
            is Long -> ChainId(value.toULong())
            is Number -> ChainId(value.toLong().toULong())
            is String -> ChainId(value.toULong())
            else -> error("Unexpected value of type Int: $value of ${value::class.qualifiedName}")
        }
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val v = when (value) {
            is UInt -> value.toLong()
            else -> value
        }
        super.setParameter(stmt, index, v)
    }

    override fun notNullValueToDB(value: Any): Any {
        val v = when (value) {
            is ChainId -> value.value.toLong()
            else -> value
        }
        return super.notNullValueToDB(v)
    }
}

object ChainTable : IdTable<ChainId>("chain") {
    override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
    override val primaryKey = PrimaryKey(id)

    val name = varchar("name", 10485760)
    val nativeTokenSymbol = varchar("native_token_symbol", 10485760)
    val nativeTokenName = varchar("native_token_name", 10485760)
    val nativeTokenDecimals = ubyte("native_token_decimals")
}

class ChainEntity(id: EntityID<ChainId>) : Entity<ChainId>(id) {
    var name by ChainTable.name
    var nativeTokenSymbol by ChainTable.nativeTokenSymbol.transform(
        toColumn = { it.value },
        toReal = { Symbol(it) },
    )
    var nativeTokenName by ChainTable.nativeTokenName
    var nativeTokenDecimals by ChainTable.nativeTokenDecimals

    companion object : EntityClass<ChainId, ChainEntity>(ChainTable) {
        override fun all(): SizedIterable<ChainEntity> =
            wrapRows(table.selectAll().orderBy(table.id, SortOrder.ASC).notForUpdate())
    }
}
