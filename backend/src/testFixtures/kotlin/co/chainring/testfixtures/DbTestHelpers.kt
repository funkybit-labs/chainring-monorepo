package co.chainring.testfixtures

import co.chainring.core.model.Address
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.toFundamentalUnits
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

object DbTestHelpers {
    fun createChain(
        id: ChainId,
        name: String,
        jsonRpcUrl: String = "",
        blockExplorerNetName: String = "",
        blockExplorerUrl: String = "",
    ): ChainEntity =
        ChainEntity.create(id, name, jsonRpcUrl, blockExplorerNetName, blockExplorerUrl)

    fun createNativeSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
        withdrawalFee: BigInteger = BigInteger.ZERO,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = null,
            decimals = decimals,
            description = "native coin",
            withdrawalFee = withdrawalFee,
        )

    fun createSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
        withdrawalFee: BigInteger = BigInteger.ZERO,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = Address.generate(),
            decimals = decimals,
            description = "$name coin",
            withdrawalFee = withdrawalFee,
        )

    fun createMarket(
        baseSymbol: SymbolEntity,
        quoteSymbol: SymbolEntity,
        tickSize: BigDecimal,
        lastPrice: BigDecimal,
    ): MarketEntity =
        MarketEntity.create(baseSymbol, quoteSymbol, tickSize, lastPrice, "test")

    fun createWallet(address: Address = Address.generate()): WalletEntity =
        WalletEntity.getOrCreate(address)

    fun createOrder(
        market: MarketEntity,
        wallet: WalletEntity,
        side: OrderSide,
        type: OrderType,
        amount: BigDecimal,
        price: BigDecimal?,
        status: OrderStatus,
        sequencerId: SequencerOrderId,
        id: OrderId = OrderId.generate(),
        createdAt: Instant = Clock.System.now(),
    ): OrderEntity =
        OrderEntity.new(id) {
            this.createdAt = createdAt
            this.createdBy = "system"
            this.marketGuid = market.guid
            this.walletGuid = wallet.guid
            this.status = status
            this.side = side
            this.type = type
            this.amount = amount.toFundamentalUnits(market.baseSymbol.decimals)
            this.originalAmount = this.amount
            this.price = price
            this.nonce = UUID.randomUUID().toString().replace("-", "")
            this.signature = "signature"
            this.sequencerOrderId = sequencerId
            this.sequencerTimeNs = BigInteger.ZERO
        }
}
