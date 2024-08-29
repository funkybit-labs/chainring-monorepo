package xyz.funkybit.integrationtests.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bitcoinj.core.ECKey
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import xyz.funkybit.apps.api.LinkMessage
import xyz.funkybit.apps.api.model.Balance
import xyz.funkybit.apps.api.model.BitcoinLinkAddressProof
import xyz.funkybit.apps.api.model.EvmLinkAddressProof
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.BitcoinSignature
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

data class AssetAmount(
    val symbol: SymbolInfo,
    val amount: BigDecimal,
) {
    constructor(symbol: SymbolInfo, amount: String) : this(symbol, BigDecimal(amount).setScale(symbol.decimals.toInt()))
    constructor(symbol: SymbolInfo, amount: BigInteger) : this(symbol, amount.fromFundamentalUnits(symbol.decimals).setScale(symbol.decimals.toInt()))

    val inFundamentalUnits: BigInteger =
        amount.toFundamentalUnits(symbol.decimals)

    operator fun plus(other: AssetAmount): AssetAmount {
        require(symbol.name == other.symbol.name) { "Both amounts must be of same asset" }
        return AssetAmount(symbol, amount + other.amount)
    }

    operator fun minus(other: AssetAmount): AssetAmount {
        require(symbol.name == other.symbol.name) { "Both amounts must be of same asset" }
        return AssetAmount(symbol, amount - other.amount)
    }

    operator fun times(other: BigDecimal): AssetAmount {
        return AssetAmount(symbol, (amount * other).setScale(symbol.decimals.toInt(), RoundingMode.FLOOR))
    }
}

fun Iterable<AssetAmount>.sum(): AssetAmount = reduce { acc, next -> acc + next }

fun BigDecimal.ofAsset(symbol: SymbolInfo): AssetAmount =
    AssetAmount(symbol, this.setScale(symbol.decimals.toInt()))

fun BigDecimal.inFundamentalUnits(symbol: SymbolInfo): BigInteger =
    toFundamentalUnits(symbol.decimals)

data class ExpectedBalance(
    val symbol: SymbolInfo,
    val total: BigDecimal,
    val available: BigDecimal,
) {
    constructor(assetAmount: AssetAmount) : this(assetAmount.symbol, assetAmount.amount, assetAmount.amount)
    constructor(symbol: SymbolInfo, total: AssetAmount, available: AssetAmount) : this(symbol, total = total.amount, available = available.amount)
}

fun assertBalances(expectedBalances: List<ExpectedBalance>, actualBalances: List<Balance>) {
    expectedBalances.forEach { expected ->
        val symbol = expected.symbol
        val actual = actualBalances.firstOrNull { it.symbol.value == symbol.name }

        assertNotNull(actual)
        assertEquals(
            expected.available.setScale(symbol.decimals.toInt()),
            actual.available.fromFundamentalUnits(symbol.decimals),
            "${expected.symbol} available balance does not match",
        )

        assertEquals(
            expected.total.setScale(symbol.decimals.toInt()),
            actual.total.fromFundamentalUnits(symbol.decimals),
            "${expected.symbol} total balance does not match",
        )
    }
}

fun assertAmount(expectedAmount: AssetAmount, amount: BigInteger) =
    assertEquals(expectedAmount, AssetAmount(expectedAmount.symbol, amount))

fun assertAmount(expectedAmount: AssetAmount, amount: BigDecimal) =
    assertEquals(expectedAmount, AssetAmount(expectedAmount.symbol, amount.setScale(expectedAmount.symbol.decimals.toInt())))

fun assertFee(expectedAmount: AssetAmount, amount: BigInteger, symbol: Symbol) {
    assertEquals(expectedAmount, AssetAmount(expectedAmount.symbol, amount))
    assertEquals(expectedAmount.symbol.name, symbol.value)
}

fun verifyApiReturnsSameLimits(apiClient: TestApiClient, wsMessage: Limits) {
    assertEquals(wsMessage.limits, apiClient.getLimits().limits)
}

fun signBitcoinWalletLinkProof(
    ecKey: ECKey,
    address: BitcoinAddress,
    linkAddress: EvmAddress,
    timestamp: Instant = Clock.System.now(),
): BitcoinLinkAddressProof {
    val message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees."
    val bitcoinLinkAddressMessage = "$message\nAddress: ${address.value}, LinkAddress: ${linkAddress.value}, Timestamp: $timestamp"
    val signature = BitcoinSignature(ecKey.signMessage(bitcoinLinkAddressMessage))

    return BitcoinLinkAddressProof(
        address = address,
        linkAddress = linkAddress,
        timestamp = timestamp.toString(),
        signature = signature,
    )
}

fun signEvmWalletLinkProof(
    ecKeyPair: ECKeyPair,
    address: EvmAddress,
    linkAddress: BitcoinAddress,
    chainId: ChainId = ChainId(1337U),
    timestamp: Instant = Clock.System.now(),
): EvmLinkAddressProof {
    val linkMessage = LinkMessage(
        message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees.",
        address = address.toString(),
        linkAddress = linkAddress.toString(),
        chainId = chainId,
        timestamp = timestamp.toString(),
    )
    val signature: EvmSignature = ECHelper.signData(Credentials.create(ecKeyPair), EIP712Helper.computeHash(linkMessage))

    return EvmLinkAddressProof(
        address = address,
        linkAddress = linkAddress,
        chainId = chainId,
        timestamp = timestamp.toString(),
        signature = signature,
    )
}
