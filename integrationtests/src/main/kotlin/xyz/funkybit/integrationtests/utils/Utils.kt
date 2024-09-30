package xyz.funkybit.integrationtests.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bitcoinj.core.ECKey
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import xyz.funkybit.apps.api.AuthorizeWalletAddressMessage
import xyz.funkybit.apps.api.model.AuthorizeWalletApiRequest
import xyz.funkybit.apps.api.model.Balance
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.apps.api.model.websocket.Limits
import xyz.funkybit.core.blockchain.evm.ECHelper
import xyz.funkybit.core.blockchain.evm.EIP712Helper
import xyz.funkybit.core.model.BitcoinAddress
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

    operator fun div(other: BigDecimal) = AssetAmount(
        symbol,
        (amount / other).setScale(symbol.decimals.toInt(), RoundingMode.FLOOR),
    )
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

fun signAuthorizeEvmWalletRequest(
    ecKey: ECKey,
    address: BitcoinAddress,
    authorizedAddress: EvmAddress,
    chainId: ChainId = ChainId(0U),
    timestamp: Instant = Clock.System.now(),
): AuthorizeWalletApiRequest {
    val message = "[funkybit] Please sign this message to authorize EVM wallet ${authorizedAddress.value.lowercase()}. This action will not cost any gas fees."
    val bitcoinLinkAddressMessage = "$message\nAddress: ${address.value}, Timestamp: $timestamp"
    val signature = ecKey.signMessage(bitcoinLinkAddressMessage)

    return AuthorizeWalletApiRequest(
        authorizedAddress = authorizedAddress,
        chainId = chainId,
        address = address,
        timestamp = timestamp.toString(),
        signature = signature,
    )
}

fun signAuthorizeBitcoinWalletRequest(
    ecKeyPair: ECKeyPair,
    address: EvmAddress,
    authorizedAddress: BitcoinAddress,
    chainId: ChainId = ChainId(1337U),
    timestamp: Instant = Clock.System.now(),
): AuthorizeWalletApiRequest {
    val message = "[funkybit] Please sign this message to authorize Bitcoin wallet ${authorizedAddress.value.lowercase()}. This action will not cost any gas fees."
    val signature: EvmSignature = ECHelper.signData(
        Credentials.create(ecKeyPair),
        EIP712Helper.computeHash(
            AuthorizeWalletAddressMessage(
                message = message,
                address = address.toString(),
                authorizedAddress = authorizedAddress.toString(),
                chainId = chainId,
                timestamp = timestamp.toString(),
            ),
        ),
    )

    return AuthorizeWalletApiRequest(
        authorizedAddress = authorizedAddress,
        address = address,
        chainId = chainId,
        timestamp = timestamp.toString(),
        signature = signature.value,
    )
}
