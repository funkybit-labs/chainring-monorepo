package co.chainring.apps.api

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BigIntegerJson
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import kotlinx.datetime.Clock

object Examples {

    val crateMarketOrderRequest = CreateOrderApiRequest.Market(
        nonce = "123",
        marketId = MarketId("ETH/USDC"),
        side = OrderSide.Buy,
        amount = BigIntegerJson("100"),
    )

    val crateLimitOrderRequest = CreateOrderApiRequest.Limit(
        nonce = "123",
        marketId = MarketId("ETH/USDC"),
        side = OrderSide.Buy,
        amount = BigIntegerJson("100"),
        price = BigIntegerJson("100"),
    )

    val updateMarketOrderRequest = UpdateOrderApiRequest.Market(
        id = OrderId("123"),
        amount = BigIntegerJson("100"),
    )

    val updateLimitOrderRequest = UpdateOrderApiRequest.Limit(
        id = OrderId("123"),
        amount = BigIntegerJson("100"),
        price = BigIntegerJson("100"),
    )

    val marketOrderResponse = OrderApiResponse.Market(
        id = OrderId.generate(),
        status = OrderStatus.Partial,
        marketId = MarketId("ETH/USDC"),
        side = OrderSide.Buy,
        amount = BigIntegerJson("100"),
        originalAmount = BigIntegerJson("100"),
        executions = listOf(
            Order.Execution(
                timestamp =  Clock.System.now(),
                amount = BigIntegerJson("50"),
                price = BigIntegerJson("500"),
                role = ExecutionRole.Maker,
                feeAmount = BigIntegerJson("0"),
                feeSymbol = Symbol("ETH"),
            )
        ),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
        ),
    )

    val limitOrderResponse = OrderApiResponse.Limit(
        id = OrderId.generate(),
        status = OrderStatus.Open,
        marketId = MarketId("ETH/USDC"),
        side = OrderSide.Buy,
        amount = BigIntegerJson("100"),
        price = BigIntegerJson("100"),
        originalAmount = BigIntegerJson("100"),
        executions = emptyList(),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
        ),
    )

    val USDCBalance = Balance(
        symbol = Symbol("USDC"),
        total = BigIntegerJson("0"),
        available = BigIntegerJson("0"),
        lastUpdated = Clock.System.now(),
    )

    val ETHBalance = Balance(
        symbol = Symbol("ETH"),
        total = BigIntegerJson("1000000000000"),
        available = BigIntegerJson("500000"),
        lastUpdated = Clock.System.now(),
    )
}
