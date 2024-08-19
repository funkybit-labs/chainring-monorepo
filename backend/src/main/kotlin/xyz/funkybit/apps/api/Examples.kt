package xyz.funkybit.apps.api

import kotlinx.datetime.Clock
import xyz.funkybit.apps.api.model.Balance
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CancelOrderApiResponse
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.api.model.GetOrderBookApiResponse
import xyz.funkybit.apps.api.model.Order
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.apps.api.model.websocket.OrderBook
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DepositId
import xyz.funkybit.core.model.db.ExecutionRole
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.TradeId
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import java.math.BigDecimal
import java.math.BigInteger

object Examples {

    val createMarketOrderRequest = CreateOrderApiRequest.Market(
        nonce = "123",
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = OrderAmount.Fixed(BigInteger("100")),
        signature = EvmSignature.emptySignature(),
        verifyingChainId = ChainId.empty,
    )

    val createLimitOrderRequest = CreateOrderApiRequest.Limit(
        nonce = "123",
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = OrderAmount.Fixed(BigInteger("100")),
        price = BigDecimal("100"),
        signature = EvmSignature.emptySignature(),
        verifyingChainId = ChainId.empty,
    )

    val createMarketOrderResponse = CreateOrderApiResponse(
        orderId = OrderId.generate(),
        clientOrderId = null,
        requestStatus = RequestStatus.Accepted,
        error = null,
        order = createMarketOrderRequest,
    )

    val createLimitOrderResponse = CreateOrderApiResponse(
        orderId = OrderId.generate(),
        clientOrderId = null,
        requestStatus = RequestStatus.Accepted,
        error = null,
        order = createLimitOrderRequest,
    )

    val cancelOrderRequest = CancelOrderApiRequest(
        orderId = OrderId("123"),
        amount = BigInteger("100"),
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        nonce = "123",
        signature = EvmSignature.emptySignature(),
        verifyingChainId = ChainId.empty,
    )

    val cancelOrderResponse = CancelOrderApiResponse(
        orderId = OrderId.generate(),
        requestStatus = RequestStatus.Accepted,
        error = null,
    )

    val marketOrderResponse = Order.Market(
        id = OrderId.generate(),
        clientOrderId = null,
        status = OrderStatus.Partial,
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        executions = listOf(
            Order.Execution(
                tradeId = TradeId.generate(),
                timestamp = Clock.System.now(),
                amount = BigInteger("50"),
                price = BigDecimal("500"),
                role = ExecutionRole.Maker,
                feeAmount = BigInteger("0"),
                feeSymbol = Symbol("ETH"),
                marketId = MarketId("BTC/ETH"),
            ),
        ),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
            updatedAt = null,
            closedAt = null,
            sequencerTimeNs = BigIntegerJson.ZERO,
        ),
    )

    val limitOrderResponse = Order.Limit(
        id = OrderId.generate(),
        clientOrderId = null,
        status = OrderStatus.Open,
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100"),
        price = BigDecimal("100"),
        originalAmount = BigInteger("100"),
        autoReduced = false,
        executions = listOf(
            Order.Execution(
                tradeId = TradeId.generate(),
                timestamp = Clock.System.now(),
                amount = BigInteger("50"),
                price = BigDecimal("500"),
                role = ExecutionRole.Maker,
                feeAmount = BigInteger("0"),
                feeSymbol = Symbol("ETH"),
                marketId = MarketId("BTC/ETH"),
            ),
        ),
        timing = Order.Timing(
            createdAt = Clock.System.now(),
            updatedAt = null,
            closedAt = null,
            sequencerTimeNs = BigIntegerJson.ZERO,
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
        id = WithdrawalId("id"),
        symbol = Symbol("USDC"),
        amount = BigInteger("200000"),
        status = WithdrawalStatus.Pending,
        error = null,
        createdAt = Clock.System.now(),
        txHash = TxHash.emptyHash(),
        fee = BigInteger("20"),
    )

    var deposit = Deposit(
        id = DepositId("id"),
        symbol = Symbol("USDC"),
        amount = BigInteger("200000"),
        status = Deposit.Status.Pending,
        error = null,
        createdAt = Clock.System.now(),
        txHash = TxHash.emptyHash(),
    )

    val getOrderBookApiResponse = GetOrderBookApiResponse(
        marketId = MarketId("BTC/ETH"),
        bids = listOf(
            OrderBook.Entry(
                price = "16.75",
                size = BigDecimal("1"),
            ),
            OrderBook.Entry(
                price = "16.70",
                size = BigDecimal("2"),
            ),
        ),
        asks = listOf(
            OrderBook.Entry(
                price = "16.80",
                size = BigDecimal("1"),
            ),
            OrderBook.Entry(
                price = "16.85",
                size = BigDecimal("2"),
            ),
        ),
        last = OrderBook.LastTrade(price = "16.75", OrderBook.LastTradeDirection.Up),
    )
}
