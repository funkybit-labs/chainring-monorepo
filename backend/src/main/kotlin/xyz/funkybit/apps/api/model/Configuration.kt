package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TestnetChallengeStatus

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
    val feeRates: FeeRates,
) {
    val evmChains: List<Chain>
        get() = chains.filter { it.networkType == NetworkType.Evm }

    val bitcoinChain: Chain
        get() = chains.first { it.networkType == NetworkType.Bitcoin }
}

@Serializable
data class FeeRates(
    val maker: BigDecimalJson,
    val taker: BigDecimalJson,
) {
    constructor(feeRates: xyz.funkybit.core.model.db.FeeRates) :
        this(maker = feeRates.maker.toPercents(), taker = feeRates.taker.toPercents())
}

enum class Role {
    User,
    Admin,
}

@Serializable
data class AccountConfigurationApiResponse(
    val newSymbols: List<SymbolInfo>,
    val role: Role,
    val authorizedAddresses: List<Address>,
    val testnetChallengeStatus: TestnetChallengeStatus,
    val testnetChallengeDepositSymbol: String?,
    val testnetChallengeDepositContract: Address?,
    val nickName: String?,
    val avatarUrl: String?,
    val pointsBalance: BigDecimalJson,
)

@Serializable
data class Chain(
    val id: ChainId,
    val name: String,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
    val jsonRpcUrl: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val networkType: NetworkType,
)

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
)

@Serializable
data class SymbolInfo(
    val name: String,
    val description: String,
    val contractAddress: Address?,
    val decimals: UByte,
    val faucetSupported: Boolean,
    val iconUrl: String,
    val withdrawalFee: BigIntegerJson,
)

fun SymbolEntity.toSymbolInfo(faucetMode: FaucetMode) = SymbolInfo(
    this.name,
    this.description,
    this.contractAddress,
    this.decimals,
    this.faucetSupported(faucetMode),
    this.iconUrl,
    this.withdrawalFee,
)

@Serializable
data class Market(
    val id: MarketId,
    val baseSymbol: Symbol,
    val baseDecimals: Int,
    val quoteSymbol: Symbol,
    val quoteDecimals: Int,
    val tickSize: BigDecimalJson,
    val lastPrice: BigDecimalJson,
    val minFee: BigIntegerJson,
)
