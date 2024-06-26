package co.chainring.core.model.db

import co.chainring.apps.api.FaucetMode
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.web3j.crypto.Keys

@Serializable
@JvmInline
value class SymbolId(override val value: String) : EntityId {
    constructor(chainId: ChainId, name: String) : this("s_${name.lowercase()}_$chainId")

    override fun toString(): String = value
}

object SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId) {
    val name = varchar("name", 10485760)
    val chainId = reference("chain_id", ChainTable)
    val contractAddress = varchar("contract_address", 10485760).nullable()
    val decimals = ubyte("decimals")
    val description = varchar("description", 10485760)
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val iconUrl = varchar("icon_url", 10485760).nullable()
    val addToWallets = bool("add_to_wallets").default(false)

    init {
        uniqueIndex(
            customIndexName = "uix_symbol_chain_id_name",
            columns = arrayOf(chainId, name),
        )
        uniqueIndex(
            customIndexName = "uix_symbol_chain_id_contract_address",
            columns = arrayOf(chainId, contractAddress),
        )
    }
}

class SymbolEntity(guid: EntityID<SymbolId>) : GUIDEntity<SymbolId>(guid) {
    companion object : EntityClass<SymbolId, SymbolEntity>(SymbolTable) {
        fun create(
            name: String,
            chainId: ChainId,
            contractAddress: Address?,
            decimals: UByte,
            description: String,
            addToWallets: Boolean = false,
        ) = SymbolEntity.new(SymbolId(chainId, name)) {
            this.name = name
            this.chainId = EntityID(chainId, ChainTable)
            this.contractAddress = contractAddress
            this.decimals = decimals
            this.description = description
            this.createdAt = Clock.System.now()
            this.createdBy = "system"
            this.addToWallets = addToWallets
        }

        fun forChain(chainId: ChainId): List<SymbolEntity> =
            SymbolEntity
                .find { SymbolTable.chainId.eq(chainId) }
                .orderBy(Pair(SymbolTable.name, SortOrder.ASC))
                .toList()

        fun forChainAndName(chainId: ChainId, name: String): SymbolEntity =
            SymbolEntity
                .find { SymbolTable.chainId.eq(chainId) and SymbolTable.name.eq(name) }
                .single()

        fun forName(name: String): SymbolEntity =
            SymbolEntity
                .find { SymbolTable.name.eq(name) }
                .single()

        fun forName(name: Symbol): SymbolEntity =
            SymbolEntity
                .find { SymbolTable.name.eq(name.value) }
                .single()

        fun forChainAndContractAddress(chainId: ChainId, contractAddress: Address?) =
            SymbolEntity
                .find { SymbolTable.chainId.eq(chainId).and(contractAddress?.let { SymbolTable.contractAddress.eq(it.value) } ?: SymbolTable.contractAddress.isNull()) }
                .single()

        fun symbolsToAddToWallet(address: Address): List<SymbolEntity> {
            val subquery = WalletTable.select(WalletTable.addedSymbols).adjustWhere {
                WalletTable.address.eq(address.value)
            }
            return SymbolTable.selectAll().adjustWhere {
                SymbolTable.addToWallets.eq(true) and
                    SymbolTable.name.notInList(subquery.map { it[WalletTable.addedSymbols].toList() }.flatten())
            }.map {
                SymbolEntity.wrapRow(it)
            }
        }
    }

    var name by SymbolTable.name
    var chainId by SymbolTable.chainId
    var contractAddress by SymbolTable.contractAddress.transform(
        toColumn = { it?.value },
        toReal = { it?.let { Address(Keys.toChecksumAddress(it)) } },
    )
    var decimals by SymbolTable.decimals
    var description by SymbolTable.description
    var createdAt by SymbolTable.createdAt
    var createdBy by SymbolTable.createdBy
    var iconUrl by SymbolTable.iconUrl
    var addToWallets by SymbolTable.addToWallets

    fun swapOptions(): List<SymbolEntity> =
        MarketEntity.all().mapNotNull {
            if (it.baseSymbol == this) {
                it.quoteSymbol
            } else if (it.quoteSymbol == this) {
                it.baseSymbol
            } else {
                null
            }
        }

    fun faucetSupported(faucetMode: FaucetMode): Boolean =
        when (faucetMode) {
            FaucetMode.Off -> false
            FaucetMode.AllSymbols -> true
            FaucetMode.OnlyNative -> contractAddress == null
            FaucetMode.OnlyERC20 -> contractAddress != null
        }
}
