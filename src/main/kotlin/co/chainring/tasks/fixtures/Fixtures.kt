package co.chainring.tasks.fixtures

import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import java.math.BigDecimal
import java.math.BigInteger

data class Fixtures(
    val chains: List<Chain>,
    val symbols: List<Symbol>,
    val markets: List<Market>,
    val wallets: List<Wallet>
) {
    data class Chain(
        val id: ChainId,
        val name: String
    )

    data class Symbol(
        val name: String,
        val chainId: ChainId,
        val isNative: Boolean,
        val decimals: Int,
        val contractAddress: Address? = null,
    )

    data class SymbolId(val name: String, val chainId: ChainId)

    data class Market(
        val baseSymbol: SymbolId,
        val quoteSymbol: SymbolId
    )

    data class Wallet(
        val privateKeyHex: String,
        val address: Address,
        val balances: Map<SymbolId, BigInteger>
    )
}

val chainringDevChainId = ChainId(1337u)

val localDevFixtures = Fixtures(
    chains = listOf(
        Fixtures.Chain(chainringDevChainId, "chainring-dev")
    ),
    symbols = listOf(
        // BTC is the native token since we would be on Bitcoin L2
        Fixtures.Symbol(name = "BTC", chainId = chainringDevChainId, isNative = true, 18),
        // ETH is bridged and represented by an ERC20 token
        Fixtures.Symbol(name = "ETH", chainId = chainringDevChainId, isNative = false, 18),
        Fixtures.Symbol(name = "USDC", chainId = chainringDevChainId, isNative = false, 6),
        Fixtures.Symbol(name = "DAI", chainId = chainringDevChainId, isNative = false, 18)
    ),
    markets = listOf(
        Fixtures.Market(
            baseSymbol = Fixtures.SymbolId("BTC", chainringDevChainId),
            quoteSymbol = Fixtures.SymbolId("ETH", chainringDevChainId)
        ),
        Fixtures.Market(
            baseSymbol = Fixtures.SymbolId("USDC", chainringDevChainId),
            quoteSymbol = Fixtures.SymbolId("DAI", chainringDevChainId)
        ),
    ),
    wallets = listOf(
        Fixtures.Wallet(
            privateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
            address = Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
            balances = mapOf(
                Fixtures.SymbolId("BTC", chainringDevChainId) to BigDecimal("10").toFundamentalUnits(18),
                Fixtures.SymbolId("ETH", chainringDevChainId) to BigDecimal("100").toFundamentalUnits(18),
                Fixtures.SymbolId("USDC", chainringDevChainId) to BigDecimal("100000").toFundamentalUnits(6),
                Fixtures.SymbolId("DAI", chainringDevChainId) to BigDecimal("100000").toFundamentalUnits(18),
            )
        ),
        Fixtures.Wallet(
            privateKeyHex = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
            address = Address("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"),
            balances = mapOf(
                Fixtures.SymbolId("BTC", chainringDevChainId) to BigDecimal("10").toFundamentalUnits(18),
                Fixtures.SymbolId("ETH", chainringDevChainId) to BigDecimal("100").toFundamentalUnits(18),
                Fixtures.SymbolId("USDC", chainringDevChainId) to BigDecimal("100000").toFundamentalUnits(6),
                Fixtures.SymbolId("DAI", chainringDevChainId) to BigDecimal("100000").toFundamentalUnits(18),
            )
        )
    )
)

private fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return (this * BigDecimal("1e$decimals")).toBigInteger()
}
