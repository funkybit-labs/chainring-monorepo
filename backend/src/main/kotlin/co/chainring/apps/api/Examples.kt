package co.chainring.apps.api

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrderApiResponse
import co.chainring.core.model.Instrument
import co.chainring.core.model.OrderId
import co.chainring.core.model.Symbol
import kotlinx.datetime.Clock

object Examples {

    val marketOrderResponse = OrderApiResponse.Market(
        id = OrderId.generate(),
        status = Order.Status.Open,
        instrument = Instrument("BTC/ETH"),
        side = Order.Side.Buy,
        amount = BigDecimalJson("0.1"),
        timeInForce = Order.TimeInForce.GoodTillCancelled,
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
