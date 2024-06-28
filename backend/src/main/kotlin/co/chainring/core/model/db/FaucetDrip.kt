package co.chainring.core.model.db

import co.chainring.core.model.Address
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
@JvmInline
value class FaucetDripId(override val value: String) : EntityId {
    companion object {
        fun generate(): FaucetDripId = FaucetDripId(TypeId.generate("drip").toString())
    }

    override fun toString(): String = value
}

object FaucetDripTable : GUIDTable<FaucetDripId>("faucet_drip", ::FaucetDripId) {
    val createdAt = timestamp("created_at")
    val symbolGuid = reference("symbol_guid", SymbolTable).index()
    val walletAddress = varchar("address", 10485760).index()
    val ipAddress = varchar("ip", 10485760).index()
}

class FaucetDripEntity(guid: EntityID<FaucetDripId>) : GUIDEntity<FaucetDripId>(guid) {
    companion object : EntityClass<FaucetDripId, FaucetDripEntity>(FaucetDripTable) {
        private val faucetRefreshInterval = System.getenv("FAUCET_REFRESH_INTERVAL")?.let { Duration.parse(it) } ?: 1.days
        fun create(symbol: SymbolEntity, walletAddress: Address, ipAddress: String): FaucetDripEntity {
            return FaucetDripEntity.new(FaucetDripId.generate()) {
                this.createdAt = Clock.System.now()
                this.symbol = symbol
                this.walletAddress = walletAddress.value
                this.ipAddress = ipAddress
            }
        }

        fun eligible(symbol: SymbolEntity, walletAddress: Address, ipAddress: String): Boolean {
            val refreshPeriodStart = Clock.System.now().minus(faucetRefreshInterval)
            return (
                FaucetDripTable.select(FaucetDripTable.guid.count()).where {
                    FaucetDripTable.symbolGuid.eq(symbol.guid).and(
                        FaucetDripTable.createdAt.greater(refreshPeriodStart),
                    ).and(
                        FaucetDripTable.ipAddress.eq(ipAddress) or FaucetDripTable.walletAddress.eq(walletAddress.value),
                    )
                }.firstOrNull()?.getOrNull(FaucetDripTable.guid.count()) ?: 0L
                ) == 0L
        }
    }

    var createdAt by FaucetDripTable.createdAt
    var symbolGuid by FaucetDripTable.symbolGuid
    var symbol by SymbolEntity referencedOn FaucetDripTable.symbolGuid
    var walletAddress by FaucetDripTable.walletAddress
    var ipAddress by FaucetDripTable.ipAddress
}
