package co.chainring

import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderResponse
import co.chainring.sequencer.proto.order
import co.chainring.testutils.AppUnderTestRunner
import io.grpc.ManagedChannelBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

@ExtendWith(AppUnderTestRunner::class)
class TestSequencer {

    @Test
    fun `Test sequencer`() {
        suspend {
            val channel = ManagedChannelBuilder.forAddress("localhost", 5338).usePlaintext().build()
            val stub = GatewayGrpcKt.GatewayCoroutineStub(channel)

            // place an order and see that it gets accepted
            val response = stub.addOrder(
                order {
                    guid = UUID.randomUUID().toString()
                    market = "BTC/ETH"
                    amount = "0x12345"
                    price = 17.17
                    address = "0xccCCccCCccCCccCCccCCccCCccCCccCCccCCccCC"
                    orderType = Order.OrderType.MarketBuy
                },
            )
            assertEquals(OrderResponse.OrderDisposition.Accepted, response.disposition)

            // place another order and see that it gets the next sequence number
            val response2 = stub.addOrder(
                order {
                    guid = UUID.randomUUID().toString()
                    market = "BTC/ETH"
                    amount = "0x54321"
                    price = 18.18
                    address = "0xccCCccCCccCCccCCccCCccCCccCCccCCccCCccCC"
                    orderType = Order.OrderType.MarketSell
                },
            )
            assertEquals(OrderResponse.OrderDisposition.Accepted, response2.disposition)
            assertEquals(response.sequence + 1, response2.sequence)
        }
    }
}
