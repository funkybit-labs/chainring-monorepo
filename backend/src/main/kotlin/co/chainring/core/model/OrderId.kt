package co.chainring.core.model

import co.chainring.core.model.db.EntityId
import de.fxlae.typeid.TypeId
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class OrderId(override val value: String) : EntityId {
    companion object {
        fun generate(): OrderId = OrderId(TypeId.generate("order").toString())
    }

    override fun toString(): String = value
}
