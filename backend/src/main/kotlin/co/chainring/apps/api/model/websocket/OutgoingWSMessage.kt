package co.chainring.apps.api.model.websocket

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.BigDecimalSerializer
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.apps.api.model.BigIntegerSerializer
import co.chainring.apps.api.model.MarketLimits
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.Trade
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.TradeId
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.serializer
import java.math.BigDecimal
import java.math.BigInteger

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class OutgoingWSMessage {
    @Serializable
    @SerialName("Publish")
    data class Publish(
        val topic: SubscriptionTopic,
        val data: Publishable,
    ) : OutgoingWSMessage()
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class Publishable

@Serializable
@SerialName("OrderBook")
data class OrderBook(
    val marketId: MarketId,
    val buy: List<OrderBookEntry>,
    val sell: List<OrderBookEntry>,
    val last: LastTrade,
) : Publishable()

@Serializable
data class OrderBookEntry(
    val price: String,
    val size: BigDecimalJson,
)

@Serializable
enum class LastTradeDirection {
    Up,
    Down,
    Unchanged,
}

@Serializable
data class LastTrade(
    val price: String,
    val direction: LastTradeDirection,
)

@Serializable
@SerialName("Prices")
data class Prices(
    val market: MarketId,
    val duration: OHLCDuration,
    val ohlc: List<OHLC>,
    val full: Boolean,
    val dailyChange: Double,
) : Publishable()

@Serializable
data class OHLC(
    val start: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val duration: OHLCDuration,
)

@Serializable
@SerialName("MyTrades")
data class MyTrades(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MyTradesCreated")
data class MyTradesCreated(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MarketTradesCreated")
data class MarketTradesCreated(
    val marketId: MarketId,
    val trades: List<Trade>,
) : Publishable() {
    @Serializable(with = Trade.AsArraySerializer::class)
    data class Trade(
        val id: TradeId,
        val type: OrderSide,
        val amount: BigIntegerJson,
        val price: BigDecimalJson,
        val timestamp: Instant,
    ) {
        constructor(takerOrderExecution: OrderExecutionEntity) :
            this(
                takerOrderExecution.tradeGuid.value,
                takerOrderExecution.order.side,
                takerOrderExecution.trade.amount,
                takerOrderExecution.trade.price,
                takerOrderExecution.timestamp,
            )

        object AsArraySerializer : KSerializer<Trade> {
            private val tradeIdSerializer = serializer(TradeId::class.javaObjectType)
            private val orderSideSerializer = serializer(OrderSide::class.javaObjectType)
            private val amountSerializer = BigIntegerSerializer
            private val priceSerializer = BigDecimalSerializer
            private val timestampSerializer = serializer<Long>()

            @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
            override val descriptor: SerialDescriptor = buildSerialDescriptor("MarketTradesCreated.Trade", StructureKind.LIST) {
                element("id", tradeIdSerializer.descriptor)
                element("type", orderSideSerializer.descriptor)
                element("amount", amountSerializer.descriptor)
                element("price", priceSerializer.descriptor)
                element("timestamp", timestampSerializer.descriptor)
            }

            override fun serialize(encoder: Encoder, value: Trade) =
                encoder.encodeCollection(descriptor, 5) {
                    encodeSerializableElement(tradeIdSerializer.descriptor, 0, tradeIdSerializer, value.id)
                    encodeSerializableElement(orderSideSerializer.descriptor, 1, orderSideSerializer, value.type)
                    encodeSerializableElement(amountSerializer.descriptor, 2, amountSerializer, value.amount)
                    encodeSerializableElement(priceSerializer.descriptor, 3, priceSerializer, value.price)
                    encodeSerializableElement(timestampSerializer.descriptor, 4, timestampSerializer, value.timestamp.toEpochMilliseconds())
                }

            override fun deserialize(decoder: Decoder): Trade =
                decoder.decodeStructure(descriptor) {
                    var id: TradeId? = null
                    var type: OrderSide? = null
                    var amount: BigInteger? = null
                    var price: BigDecimal? = null
                    var timestamp: Instant? = null

                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            0 -> id = decodeSerializableElement(tradeIdSerializer.descriptor, 0, tradeIdSerializer) as TradeId
                            1 -> type = decodeSerializableElement(orderSideSerializer.descriptor, 1, orderSideSerializer) as OrderSide
                            2 -> amount = decodeSerializableElement(amountSerializer.descriptor, 2, amountSerializer)
                            3 -> price = decodeSerializableElement(priceSerializer.descriptor, 3, priceSerializer)
                            4 -> timestamp = Instant.fromEpochMilliseconds(decodeSerializableElement(timestampSerializer.descriptor, 4, timestampSerializer))
                            CompositeDecoder.DECODE_DONE -> break
                            else -> error("Unexpected index: $index")
                        }
                    }
                    Trade(
                        id ?: throw SerializationException("Trade id is missing in json array"),
                        type = type ?: throw SerializationException("Trade type is missing in json array"),
                        amount = amount ?: throw SerializationException("Trade amount is missing in json array"),
                        price = price ?: throw SerializationException("Trade price is missing in json array"),
                        timestamp = timestamp ?: throw SerializationException("Trade timestamp is missing in json array"),
                    )
                }
        }
    }
}

@Serializable
@SerialName("MyTradesUpdated")
data class MyTradesUpdated(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MyOrders")
data class MyOrders(
    val orders: List<Order>,
) : Publishable()

@Serializable
@SerialName("Balances")
data class Balances(
    val balances: List<Balance>,
) : Publishable()

@Serializable
@SerialName("MyOrderCreated")
data class MyOrderCreated(
    val order: Order,
) : Publishable()

@Serializable
@SerialName("MyOrderUpdated")
data class MyOrderUpdated(
    val order: Order,
) : Publishable()

@Serializable
@SerialName("Limits")
data class Limits(
    val limits: List<
        @Serializable(with = MarketLimits.AsArraySerializer::class)
        MarketLimits,
        >,
) : Publishable()
