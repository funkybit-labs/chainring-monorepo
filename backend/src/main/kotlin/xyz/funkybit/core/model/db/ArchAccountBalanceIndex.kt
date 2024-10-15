package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.WalletAndSymbol
import xyz.funkybit.core.model.rpc.ArchNetworkRpc

@Serializable
@JvmInline
value class ArchAccountBalanceIndexId(override val value: String) : EntityId {
    companion object {
        fun generate(): ArchAccountBalanceIndexId = ArchAccountBalanceIndexId(TypeId.generate("aabalidx").toString())
    }

    override fun toString(): String = value
}

enum class ArchAccountBalanceIndexStatus {
    Pending,
    Assigning,
    Assigned,
    Failed,
}

object ArchAccountBalanceIndexTable : GUIDTable<ArchAccountBalanceIndexId>("arch_account_balance_index", ::ArchAccountBalanceIndexId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val archAccountGuid = reference("arch_account_guid", ArchAccountTable)
    val addressIndex = integer("address_index")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").nullable()
    val status = customEnumeration(
        "status",
        "ArchAccountBalanceIndexStatus",
        { value -> ArchAccountBalanceIndexStatus.valueOf(value as String) },
        { PGEnum("ArchAccountBalanceIndexStatus", it) },
    ).index()
    val archTransactionGuid = reference(
        "arch_tx_guid",
        BlockchainTransactionTable,
    ).nullable()

    init {
        uniqueIndex(
            customIndexName = "uix_bal_idx_arch_account_wallet_guid",
            columns = arrayOf(walletGuid, archAccountGuid),
        )
    }
}

data class CreateArchAccountBalanceIndexAssignment(
    val walletGuid: EntityID<WalletId>,
    val archAccountGuid: EntityID<ArchAccountId>,
)

data class UpdateArchAccountBalanceIndexAssignment(
    val entity: ArchAccountBalanceIndexEntity,
    val addressIndex: Int,
)

data class ArchAccountBalanceInfo(
    val entity: ArchAccountBalanceIndexEntity,
    val archAccountAddress: ArchNetworkRpc.Pubkey,
    val walletAddress: BitcoinAddress,
)

class ArchAccountBalanceIndexEntity(guid: EntityID<ArchAccountBalanceIndexId>) : GUIDEntity<ArchAccountBalanceIndexId>(guid) {
    companion object : EntityClass<ArchAccountBalanceIndexId, ArchAccountBalanceIndexEntity>(ArchAccountBalanceIndexTable) {

        fun batchCreate(createAssignments: List<CreateArchAccountBalanceIndexAssignment>) {
            if (createAssignments.isEmpty()) {
                return
            }
            val now = Clock.System.now()
            ArchAccountBalanceIndexTable.batchInsert(createAssignments) {
                this[ArchAccountBalanceIndexTable.guid] = ArchAccountBalanceIndexId.generate()
                this[ArchAccountBalanceIndexTable.walletGuid] = it.walletGuid
                this[ArchAccountBalanceIndexTable.archAccountGuid] = it.archAccountGuid
                this[ArchAccountBalanceIndexTable.addressIndex] = Int.MAX_VALUE
                this[ArchAccountBalanceIndexTable.status] = ArchAccountBalanceIndexStatus.Pending
                this[ArchAccountBalanceIndexTable.createdAt] = now
            }
        }

        fun findAllForStatus(archAccountIndexStatus: ArchAccountBalanceIndexStatus, limit: Int? = null): List<ArchAccountBalanceInfo> {
            return ArchAccountBalanceIndexTable
                .join(WalletTable, JoinType.INNER, WalletTable.guid, ArchAccountBalanceIndexTable.walletGuid)
                .join(ArchAccountTable, JoinType.INNER, ArchAccountTable.guid, ArchAccountBalanceIndexTable.archAccountGuid)
                .selectAll()
                .where { ArchAccountBalanceIndexTable.status.eq(archAccountIndexStatus) }
                .orderBy(ArchAccountBalanceIndexTable.createdAt to SortOrder.DESC)
                .let {
                    if (limit == null) {
                        it
                    } else {
                        it.limit(limit)
                    }
                }
                .map {
                    ArchAccountBalanceInfo(
                        entity = ArchAccountBalanceIndexEntity.wrapRow(it),
                        archAccountAddress = ArchAccountEntity.wrapRow(it).rpcPubkey(),
                        walletAddress = WalletEntity.wrapRow(it).address as BitcoinAddress,
                    )
                }
        }

        fun findForWalletAddressAndSymbol(walletAddress: BitcoinAddress, symbolEntity: SymbolEntity): ArchAccountBalanceIndexEntity? {
            return ArchAccountBalanceIndexTable
                .join(WalletTable, JoinType.INNER, WalletTable.guid, ArchAccountBalanceIndexTable.walletGuid)
                .join(ArchAccountTable, JoinType.INNER, ArchAccountTable.guid, ArchAccountBalanceIndexTable.archAccountGuid)
                .selectAll()
                .where { WalletTable.address.eq(walletAddress.value) and ArchAccountTable.symbolGuid.eq(symbolEntity.guid) }
                .map {
                    ArchAccountBalanceIndexEntity.wrapRow(it)
                }.firstOrNull()
        }

        fun findForWalletsAndSymbols(walletIds: List<WalletId>, symbolIds: List<SymbolId>): Map<WalletAndSymbol, Pair<ArchAccountBalanceIndexEntity, BitcoinAddress>> {
            return ArchAccountBalanceIndexTable
                .join(
                    WalletTable,
                    JoinType.INNER,
                    ArchAccountBalanceIndexTable.walletGuid,
                    WalletTable.guid,
                )
                .join(
                    ArchAccountTable,
                    JoinType.INNER,
                    ArchAccountTable.guid,
                    ArchAccountBalanceIndexTable.archAccountGuid,
                )
                .selectAll()
                .where { ArchAccountBalanceIndexTable.walletGuid.inList(walletIds) and ArchAccountTable.symbolGuid.inList(symbolIds) }
                .associate {
                    WalletAndSymbol(
                        it[ArchAccountBalanceIndexTable.walletGuid].value,
                        it[ArchAccountTable.symbolGuid]!!.value,
                    ) to Pair(ArchAccountBalanceIndexEntity.wrapRow(it), BitcoinAddress.canonicalize(it[WalletTable.address]))
                }
        }

        fun updateToAssigning(archAccountBalanceIndexEntities: List<ArchAccountBalanceIndexEntity>, blockchainTransactionEntity: BlockchainTransactionEntity) {
            ArchAccountBalanceIndexTable.update({ ArchAccountBalanceIndexTable.guid.inList(archAccountBalanceIndexEntities.map { it.guid }) }) {
                it[archTransactionGuid] = blockchainTransactionEntity.guid
                it[status] = ArchAccountBalanceIndexStatus.Assigning
            }
        }

        fun updateToAssigned(updateAssignments: List<UpdateArchAccountBalanceIndexAssignment>) {
            BatchUpdateStatement(ArchAccountBalanceIndexTable).apply {
                updateAssignments.forEach {
                    addBatch(it.entity.id)
                    this[ArchAccountBalanceIndexTable.status] = ArchAccountBalanceIndexStatus.Assigned
                    this[ArchAccountBalanceIndexTable.addressIndex] = it.addressIndex
                }
                execute(TransactionManager.current())
            }
        }
    }

    var walletGuid by ArchAccountBalanceIndexTable.walletGuid
    var wallet by WalletEntity referencedOn ArchAccountBalanceIndexTable.walletGuid

    var archAccountGuid by ArchAccountBalanceIndexTable.archAccountGuid
    var archAccount by ArchAccountEntity referencedOn ArchAccountBalanceIndexTable.archAccountGuid

    var archTransactionGuid by ArchAccountBalanceIndexTable.archTransactionGuid
    var archTransaction by BlockchainTransactionEntity optionalReferencedOn ArchAccountBalanceIndexTable.archTransactionGuid

    var addressIndex by ArchAccountBalanceIndexTable.addressIndex
    var status by ArchAccountBalanceIndexTable.status

    var createdAt by ArchAccountBalanceIndexTable.createdAt
    var updatedAt by ArchAccountBalanceIndexTable.updatedAt
}
