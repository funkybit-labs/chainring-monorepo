package co.chainring.core.model.db

import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

@Serializable
@JvmInline
value class ContractId(override val value: String) : EntityId {
    companion object {
        fun generate(): ContractId = ContractId(TypeId.generate("dsm").toString())
    }

    override fun toString(): String = value
}

enum class Chain {
    Ethereum,
}

object DeployedSmartContractTable : GUIDTable<ContractId>("deployed_smart_contract", ::ContractId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val name = varchar("name", 10485760)
    val chain = customEnumeration(
        "chain",
        "Chain",
        { value -> Chain.valueOf(value as String) },
        { PGEnum("Chain", it) },
    )
    val address = varchar("address", 10485760)
    val deprecated = bool("deprecated")

    init {
        uniqueIndex(
            customIndexName = "deployed_smart_contract_name_chain",
            columns = arrayOf(name, chain),
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
            chain: Chain,
            address: Address,
        ) = DeployedSmartContractEntity.new(ContractId.generate()) {
            this.createdAt = Clock.System.now()
            this.createdBy = "system"
            this.name = name
            this.chain = chain
            this.address = address
            this.deprecated = false
        }

        fun validContracts(): List<DeployedSmartContractEntity> =
            DeployedSmartContractEntity
                .find {
                    DeployedSmartContractTable.deprecated.eq(false)
                }
                .toList()
    }

    var createdAt by DeployedSmartContractTable.createdAt
    var createdBy by DeployedSmartContractTable.createdBy
    var name by DeployedSmartContractTable.name
    var chain by DeployedSmartContractTable.chain
    var address by DeployedSmartContractTable.address.transform(
        toColumn = { it.value },
        toReal = { Address(it) },
    )
    var deprecated by DeployedSmartContractTable.deprecated
}
