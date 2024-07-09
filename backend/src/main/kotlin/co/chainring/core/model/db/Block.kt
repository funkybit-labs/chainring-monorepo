package co.chainring.core.model.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import java.math.BigInteger

@Serializable
@JvmInline
value class BlockHash(override val value: String) : EntityId {
    override fun toString(): String = value
}

object BlockTable : GUIDTable<BlockHash>("block", ::BlockHash) {
    val chainId = reference("chain_id", ChainTable)
    val number = decimal("number", 30, 0)
    val parentGuid = varchar("parent_guid", 10485760).index()

    init {
        index(true, number, chainId)
    }
}

class BlockEntity(guid: EntityID<BlockHash>) : GUIDEntity<BlockHash>(guid) {
    companion object : EntityClass<BlockHash, BlockEntity>(BlockTable) {
        fun create(hash: BlockHash, number: BigInteger, parentHash: BlockHash, chainId: ChainId): BlockEntity =
            BlockEntity.new(hash) {
                this.number = number
                this.parentGuid = parentHash
                this.chainId = EntityID(chainId, ChainTable)
            }

        fun getRecentBlocksUpToNumber(blockNumber: BigInteger, chainId: ChainId): List<BlockEntity> =
            BlockEntity.find {
                BlockTable.chainId.eq(chainId).and(
                    BlockTable.number.greaterEq(blockNumber.toBigDecimal()),
                )
            }.orderBy(Pair(BlockTable.number, SortOrder.DESC)).toList()
    }

    var chainId by BlockTable.chainId
    var number by BlockTable.number.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var parentGuid by BlockTable.parentGuid.transform(
        toReal = ::BlockHash,
        toColumn = { it.value },
    )
}
