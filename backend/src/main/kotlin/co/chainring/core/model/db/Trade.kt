package co.chainring.core.model.db

import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.utils.toFundamentalUnits
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@JvmInline
value class TradeId(override val value: String) : EntityId {
    companion object {
        fun generate(): TradeId = TradeId(TypeId.generate("trade").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class SettlementStatus {
    Pending,
    Completed,
    Failed,
    ;

    fun isFinal(): Boolean {
        return this in listOf(SettlementStatus.Completed, SettlementStatus.Failed)
    }
}

object TradeTable : GUIDTable<TradeId>("trade", ::TradeId) {
    val createdAt = timestamp("created_at")
    val marketGuid = reference("market_guid", MarketTable).index()
    val timestamp = timestamp("timestamp")
    val amount = decimal("amount", 30, 0)
    val price = decimal("price", 30, 18)
    val settlementStatus = customEnumeration(
        "settlement_status",
        "SettlementStatus",
        { value -> SettlementStatus.valueOf(value as String) },
        { PGEnum("SettlementStatus", it) },
    )
    val settledAt = timestamp("settled_at").nullable()
}

class TradeEntity(guid: EntityID<TradeId>) : GUIDEntity<TradeId>(guid) {
    companion object : EntityClass<TradeId, TradeEntity>(TradeTable) {
        fun create(
            timestamp: Instant,
            market: MarketEntity,
            amount: BigInteger,
            price: BigDecimal,
        ) = TradeEntity.new(TradeId.generate()) {
            val now = Clock.System.now()
            this.createdAt = now
            this.timestamp = timestamp
            this.market = market
            this.amount = amount
            this.price = price
            this.settlementStatus = SettlementStatus.Pending
        }
    }

    fun toEip712Transaction(): EIP712Transaction.Trade {
        val executions = OrderExecutionEntity.findForTrade(this)
        val takerOrder = executions.first { it.role == ExecutionRole.Taker }.order
        val makerOrder = executions.first { it.role == ExecutionRole.Maker }.order
        val baseTokenAddress = this.market.baseSymbol.contractAddress ?: Address.zero
        val quoteTokenAddress = this.market.quoteSymbol.contractAddress ?: Address.zero
        val quoteDecimals = this.market.quoteSymbol.decimals.toInt()
        return EIP712Transaction.Trade(
            baseTokenAddress,
            quoteTokenAddress,
            if (takerOrder.side == OrderSide.Buy) this.amount else this.amount.negate(),
            this.price.toFundamentalUnits(quoteDecimals),
            takerOrder.toEip712Transaction(baseTokenAddress, quoteTokenAddress, quoteDecimals),
            makerOrder.toEip712Transaction(baseTokenAddress, quoteTokenAddress, quoteDecimals),
            this.guid.value,
        )
    }

    fun settle() {
        this.settledAt = Clock.System.now()
        this.settlementStatus = SettlementStatus.Completed
    }

    fun failSettlement() {
        this.settlementStatus = SettlementStatus.Failed
    }

    var createdAt by TradeTable.createdAt
    var timestamp by TradeTable.timestamp
    var marketGuid by TradeTable.marketGuid
    var market by MarketEntity referencedOn TradeTable.marketGuid
    var amount by TradeTable.amount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var price by TradeTable.price

    var settlementStatus by TradeTable.settlementStatus
    var settledAt by TradeTable.settledAt
}
