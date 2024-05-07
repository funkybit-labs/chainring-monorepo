package co.chainring.apps.api

import co.chainring.apps.api.model.Balance
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CancelOrderApiResponse
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Deposit
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.UpdateOrderApiResponse
import co.chainring.apps.api.model.Withdrawal
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import kotlinx.datetime.Clock
import java.math.BigDecimal
import java.math.BigInteger

object Examples {

    val createMarketOrderRequest = CreateOrderApiRequest.Market(
        nonce = "123",
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        signature = EvmSignature.emptySignature(),
    )

    val createLimitOrderRequest = CreateOrderApiRequest.Limit(
        nonce = "123",
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        price = BigDecimal("100"),
        signature = EvmSignature.emptySignature(),
    )

    val updateLimitOrderRequest = UpdateOrderApiRequest(
        orderId = OrderId("123"),
        amount = BigInteger("100"),
        price = BigDecimal("100"),
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        nonce = "123",
        signature = EvmSignature.emptySignature(),
    )

    val createMarketOrderResponse = CreateOrderApiResponse(
        orderId = OrderId.generate(),
        requestStatus = RequestStatus.Accepted,
        error = null,
        order = createMarketOrderRequest,
    )

    val createLimitOrderResponse = CreateOrderApiResponse(
        orderId = OrderId.generate(),
        requestStatus = RequestStatus.Accepted,
        error = null,
        order = createLimitOrderRequest,
    )

    val updateLimitOrderResponse = UpdateOrderApiResponse(
        requestStatus = RequestStatus.Accepted,
        error = null,
        order = updateLimitOrderRequest,
    )

    val cancelOrderRequest = CancelOrderApiRequest(
        orderId = OrderId("123"),
        amount = BigInteger("100"),
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        nonce = "123",
        signature = EvmSignature.emptySignature(),
    )

    val cancelOrderResponse = CancelOrderApiResponse(
        orderId = OrderId.generate(),
        requestStatus = RequestStatus.Accepted,
        error = null,
    )

    val marketOrderResponse = Order.Market(
        id = OrderId.generate(),
        status = OrderStatus.Partial,
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        originalAmount = BigInteger("100"),
        executions = listOf(
            Order.Execution(
                timestamp = Clock.System.now(),
                amount = BigInteger("50"),
                price = BigDecimal("500"),
                role = ExecutionRole.Maker,
                feeAmount = BigInteger("0"),
                feeSymbol = Symbol("ETH"),
            ),
        ),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
            updatedAt = null,
            closedAt = null,
        ),
    )

    val limitOrderResponse = Order.Limit(
        id = OrderId.generate(),
        status = OrderStatus.Open,
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        price = BigDecimal("100"),
        originalAmount = BigInteger("100"),
        executions = listOf(
            Order.Execution(
                timestamp = Clock.System.now(),
                amount = BigInteger("50"),
                price = BigDecimal("500"),
                role = ExecutionRole.Maker,
                feeAmount = BigInteger("0"),
                feeSymbol = Symbol("ETH"),
            ),
        ),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
            updatedAt = null,
            closedAt = null,
        ),
    )

    val USDCBalance = Balance(
        symbol = Symbol("USDC"),
        total = BigInteger("0"),
        available = BigInteger("0"),
        lastUpdated = Clock.System.now(),
    )

    val ETHBalance = Balance(
        symbol = Symbol("ETH"),
        total = BigInteger("1000000000000"),
        available = BigInteger("500000"),
        lastUpdated = Clock.System.now(),
    )

    var withdrawal = Withdrawal(
        WithdrawalId("id"),
        Symbol("USDC"),
        BigInteger("200000"),
        WithdrawalStatus.Pending,
        error = null,
        createdAt = Clock.System.now(),
    )

    var deposit = Deposit(
        DepositId("id"),
        Symbol("USDC"),
        BigInteger("200000"),
        Deposit.Status.Pending,
        error = null,
        createdAt = Clock.System.now(),
    )
}
