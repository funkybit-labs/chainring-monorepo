package co.chainring.tasks.fixtures

import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.SymbolId
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
        val name: String,
        val submitterAddress: Address
    )

    data class Symbol(
        val name: String,
        val chainId: ChainId,
        val isNative: Boolean,
        val decimals: Int
    ) {
        val id: SymbolId = SymbolId(chainId, name)
    }

    data class Market(
        val baseSymbol: SymbolId,
        val quoteSymbol: SymbolId,
        val tickSize: BigDecimal,
        val marketPrice: BigDecimal
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
        Fixtures.Chain(chainringDevChainId, "chainring-dev", Address("0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc"))
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
            baseSymbol = SymbolId(chainringDevChainId, "BTC"),
            quoteSymbol = SymbolId(chainringDevChainId, "ETH"),
            tickSize = "0.05".toBigDecimal(),
            marketPrice = "17.525".toBigDecimal()
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(chainringDevChainId, "USDC"),
            quoteSymbol = SymbolId(chainringDevChainId, "DAI"),
            tickSize = "0.01".toBigDecimal(),
            marketPrice = "2.05".toBigDecimal()
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(chainringDevChainId, "BTC"),
            quoteSymbol = SymbolId(chainringDevChainId, "USDC"),
            tickSize = "0.05".toBigDecimal(),
            marketPrice = "68390.000".toBigDecimal()
        ),
    ),
    wallets = listOf(
        Fixtures.Wallet(
            privateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
            address = Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
            balances = mapOf(
                SymbolId(chainringDevChainId, "BTC") to BigDecimal("10").toFundamentalUnits(18),
                SymbolId(chainringDevChainId, "ETH") to BigDecimal("100").toFundamentalUnits(18),
                SymbolId(chainringDevChainId, "USDC") to BigDecimal("100000").toFundamentalUnits(6),
                SymbolId(chainringDevChainId, "DAI") to BigDecimal("100000").toFundamentalUnits(18),
            )
        ),
        Fixtures.Wallet(
            privateKeyHex = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
            address = Address("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"),
            balances = mapOf(
                SymbolId(chainringDevChainId, "BTC") to BigDecimal("10").toFundamentalUnits(18),
                SymbolId(chainringDevChainId, "ETH") to BigDecimal("100").toFundamentalUnits(18),
                SymbolId(chainringDevChainId, "USDC") to BigDecimal("100000").toFundamentalUnits(6),
                SymbolId(chainringDevChainId, "DAI") to BigDecimal("100000").toFundamentalUnits(18),
            )
        )
    )
)

private fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return (this * BigDecimal("1e$decimals")).toBigInteger()
}
