package xyz.funkybit.core.model.db.migrations

import kotlinx.serialization.Serializable
import org.http4k.format.KotlinxSerialization
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDTable

@Suppress("ClassName")
class V73_AddBitcoinWalletStateTable : Migration() {

    @Serializable
    data class V73_UnspentUtxo(
        val utxoId: BitcoinUtxoId,
        val amount: BigIntegerJson,
        val blockHeight: Long?,
        val reservedBy: String?,
    )

    @JvmInline
    value class V73_BitcoinWalletStateId(override val value: String) : EntityId

    object V73_BitcoinWalletStateTable : GUIDTable<V73_BitcoinWalletStateId>("bitcoin_wallet_state", ::V73_BitcoinWalletStateId) {
        val address = varchar("address", 10485760).uniqueIndex()
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at").nullable()
        val lastTxId = varchar("last_tx_id", 10485760).nullable()
        val unspentUtxos = jsonb<List<V73_UnspentUtxo>>("unspent_utxos", KotlinxSerialization.json)
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V73_BitcoinWalletStateTable)
        }
    }
}
