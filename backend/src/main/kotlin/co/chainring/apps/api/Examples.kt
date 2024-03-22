package co.chainring.apps.api

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import kotlinx.datetime.Clock

object Examples {

    val crateMarketOrderRequest = CreateOrderApiRequest.Market(
        nonce = "123",
        marketId = MarketId("USDC/DAI"),
        side = OrderSide.Buy,
        amount = BigDecimalJson("0.1"),
    )

    val crateLimitOrderRequest = CreateOrderApiRequest.Limit(
        nonce = "123",
        marketId = MarketId("USDC/DAI"),
        side = OrderSide.Buy,
        amount = BigDecimalJson("0.1"),
        price = BigDecimalJson("0.1"),
    )

    val updateMarketOrderRequest = UpdateOrderApiRequest.Market(
        id = OrderId("123"),
        amount = BigDecimalJson("0.1"),
    )

    val updateLimitOrderRequest = UpdateOrderApiRequest.Limit(
        id = OrderId("123"),
        amount = BigDecimalJson("0.1"),
        price = BigDecimalJson("0.1"),
    )

    val marketOrderResponse = OrderApiResponse.Market(
        id = OrderId.generate(),
        status = OrderStatus.Open,
        marketId = MarketId("USDC/DAI"),
        side = OrderSide.Buy,
        amount = BigDecimalJson("0.1"),
        originalAmount = BigDecimalJson("0.1"),
        execution = null,
        timing = Order.Timing(
            createdAt = Clock.System.now(),
        ),
    )

    val limitOrderResponse = OrderApiResponse.Limit(
        id = OrderId.generate(),
        status = OrderStatus.Open,
        marketId = MarketId("USDC/DAI"),
        side = OrderSide.Buy,
        amount = BigDecimalJson("0.1"),
        price = BigDecimalJson("0.1"),
        originalAmount = BigDecimalJson("0.1"),
        execution = null,
        timing = Order.Timing(
            createdAt = Clock.System.now(),
        ),
    )

    val BTCBalance = Balance(
        symbol = Symbol("BTC"),
        total = BigDecimalJson("1"),
        available = BigDecimalJson("0.5"),
        lastUpdated = Clock.System.now(),
    )

    val ETHBalance = Balance(
        symbol = Symbol("ETH"),
        total = BigDecimalJson("0"),
        available = BigDecimalJson("0"),
        lastUpdated = Clock.System.now(),
    )
}
