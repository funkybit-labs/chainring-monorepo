package co.chainring.core.model

import co.chainring.core.model.db.EntityId
import de.fxlae.typeid.TypeId
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TradeId(override val value: String) : EntityId {
    companion object {
        fun generate(): TradeId = TradeId(TypeId.generate("trade").toString())
    }

    override fun toString(): String = value
}
