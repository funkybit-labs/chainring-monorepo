package co.chainring.core.model.db

import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.web3j.crypto.Keys

@Serializable
@JvmInline
value class ContractId(override val value: String) : EntityId {
    companion object {
        fun generate(): ContractId = ContractId(TypeId.generate("dsm").toString())
    }

    override fun toString(): String = value
}

object DeployedSmartContractTable : GUIDTable<ContractId>("deployed_smart_contract", ::ContractId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val name = varchar("name", 10485760)
    val chainId = reference("chain_id", ChainTable)
    val proxyAddress = varchar("proxy_address", 10485760)
    val implementationAddress = varchar("implementation_address", 10485760)
    val version = integer("version").nullable()
    val deprecated = bool("deprecated")

    init {
        uniqueIndex(
            customIndexName = "deployed_smart_contract_name_chain",
            columns = arrayOf(name, chainId),
            filterCondition = {
                deprecated.eq(false)
            },
        )
    }
}

class DeployedSmartContractEntity(guid: EntityID<ContractId>) : GUIDEntity<ContractId>(guid) {
    companion object : EntityClass<ContractId, DeployedSmartContractEntity>(DeployedSmartContractTable) {
        fun create(
            name: String,
            chainId: ChainId,
            proxyAddress: Address,
            implementationAddress: Address,
            version: Int,
        ) = DeployedSmartContractEntity.new(ContractId.generate()) {
            this.createdAt = Clock.System.now()
            this.createdBy = "system"
            this.name = name
            this.chainId = EntityID(chainId, ChainTable)
            this.proxyAddress = proxyAddress
            this.implementationAddress = implementationAddress
            this.version = version
            this.deprecated = false
        }

        fun findLastDeployedContractByNameAndChain(
            name: String,
            chainId: ChainId,
        ): DeployedSmartContractEntity? =
            DeployedSmartContractEntity
                .find {
                    DeployedSmartContractTable.name.eq(name) and DeployedSmartContractTable.chainId.eq(chainId)
                }
                .orderBy(DeployedSmartContractTable.createdAt to SortOrder.DESC)
                .firstOrNull()

        fun latestExchangeContractAddress(chainId: ChainId): Address? =
            findLastDeployedContractByNameAndChain(ContractType.Exchange.name, chainId)?.proxyAddress

        fun validContracts(chainId: ChainId): List<DeployedSmartContractEntity> =
            DeployedSmartContractEntity
                .find {
                    DeployedSmartContractTable.chainId.eq(chainId) and DeployedSmartContractTable.deprecated.eq(false)
                }
                .toList()
    }

    var createdAt by DeployedSmartContractTable.createdAt
    var createdBy by DeployedSmartContractTable.createdBy
    var name by DeployedSmartContractTable.name
    var chainId by DeployedSmartContractTable.chainId
    var proxyAddress by DeployedSmartContractTable.proxyAddress.transform(
        toColumn = { Keys.toChecksumAddress(it.value) },
        toReal = { Address(Keys.toChecksumAddress(it)) },
    )
    var implementationAddress by DeployedSmartContractTable.implementationAddress.transform(
        toColumn = { Keys.toChecksumAddress(it.value) },
        toReal = { Address(Keys.toChecksumAddress(it)) },
    )
    var version by DeployedSmartContractTable.version
    var deprecated by DeployedSmartContractTable.deprecated
}
