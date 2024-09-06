package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ArchAccountId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V78_ArchAccountTable : Migration() {

    object V78_SymbolTable : GUIDTable<SymbolId>("symbol", ::SymbolId)

    enum class V78_ArchAccountType {
        Program,
        ProgramState,
        TokenState,
    }

    enum class V78_ArchAccountStatus {
        Funded,
        Creating,
        Created,
        Initializing,
        Complete,
        Failed,
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("type")
    sealed class V78_AccountSetupState {
        @Serializable
        @SerialName("program")
        data class Program(
            val deploymentTxIds: List<TxHash>,
            val numProcessed: Int,
            val executableTxId: TxHash?,
        ) : V78_AccountSetupState()

        @Serializable
        @SerialName("program_state")
        data class ProgramState(
            val changeOwnershipTxId: TxHash,
            val initializeTxId: TxHash?,
        ) : V78_AccountSetupState()

        @Serializable
        @SerialName("token_state")
        data class TokenState(
            val changeOwnershipTxId: TxHash,
        ) : V78_AccountSetupState()
    }

    object V78_ArchAccountTable : GUIDTable<ArchAccountId>("arch_account", ::ArchAccountId) {
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()
        val utxoId = varchar("utxo_id", 10485760).uniqueIndex()
        val creationTxId = varchar("creation_tx_id", 10485760).nullable()
        val encryptedPrivateKey = varchar("encrypted_private_key", 10485760)
        val publicKey = varchar("public_key", 10485760)
        val type = customEnumeration(
            "type",
            "ArchAccountType",
            { value -> V78_ArchAccountType.valueOf(value as String) },
            { PGEnum("ArchAccountType", it) },
        ).index()
        val symbolGuid = reference("symbol_guid", V78_SymbolTable).nullable().uniqueIndex()
        val setupState = jsonb<V78_AccountSetupState>("setup_state", KotlinxSerialization.json).nullable()
        val status = customEnumeration(
            "status",
            "ArchAccountStatus",
            { value -> V78_ArchAccountStatus.valueOf(value as String) },
            { PGEnum("ArchAccountStatus", it) },
        )
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE ArchAccountType AS ENUM (${enumDeclaration<V78_ArchAccountType>()})")
            exec("CREATE TYPE ArchAccountStatus AS ENUM (${enumDeclaration<V78_ArchAccountStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V78_ArchAccountTable)

            exec("drop table arch_state_utxo_log")
            exec("drop table arch_state_utxo")
            exec("drop type  StateUtxoType")
            exec("drop type  StateUtxoStatus")
        }
    }
}
