package xyz.funkybit.core.model.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.web3j.crypto.Keys
import xyz.funkybit.core.model.EvmAddress

object BlockchainNonceTable : IntIdTable("blockchain_nonce") {
    val key = (varchar("key", 10485760))
    val chainId = reference("chain_id", ChainTable)
    val nonce = decimal("nonce", 30, 0).nullable()
    init {
        index(true, key, chainId)
    }
}

class BlockchainNonceEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BlockchainNonceEntity>(BlockchainNonceTable) {
        fun findByKeyAndChain(address: EvmAddress, chainId: ChainId): BlockchainNonceEntity? {
            return BlockchainNonceEntity.find {
                BlockchainNonceTable.key eq Keys.toChecksumAddress(address.value) and
                    BlockchainNonceTable.chainId.eq(chainId)
            }.firstOrNull()
        }

        fun create(address: EvmAddress, chainId: ChainId) {
            BlockchainNonceTable.insert {
                it[BlockchainNonceTable.key] = Keys.toChecksumAddress(address.value)
                it[BlockchainNonceTable.chainId] = chainId
            }
        }

        private fun lockForUpdate(address: EvmAddress, chainId: ChainId): BlockchainNonceEntity? {
            return BlockchainNonceTable.selectAll()
                .where {
                    BlockchainNonceTable.key eq Keys.toChecksumAddress(address.value) and BlockchainNonceTable.chainId.eq(chainId)
                }
                .forUpdate()
                .map { BlockchainNonceEntity.wrapRow(it) }
                .singleOrNull()
        }

        fun getOrCreateForUpdate(address: EvmAddress, chainId: ChainId): BlockchainNonceEntity {
            return lockForUpdate(address, chainId) ?: run {
                create(address, chainId)
                lockForUpdate(address, chainId)!!
            }
        }

        fun clear(address: EvmAddress, chainId: ChainId) {
            BlockchainNonceTable.update({
                BlockchainNonceTable.key eq Keys.toChecksumAddress(address.value) and
                    BlockchainNonceTable.chainId.eq(chainId)
            }) {
                it[this.nonce] = null
            }
        }
    }

    var key by BlockchainNonceTable.key
    var chainId by BlockchainNonceTable.chainId
    var nonce by BlockchainNonceTable.nonce.transform(
        toReal = { it?.toBigInteger() },
        toColumn = { it?.toBigDecimal() },
    )
}
