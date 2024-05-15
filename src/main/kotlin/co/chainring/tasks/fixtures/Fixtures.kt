package co.chainring.tasks.fixtures

import co.chainring.core.model.Address
import co.chainring.core.model.FeeRate
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.SymbolId
import java.math.BigDecimal
import java.math.BigInteger

data class Fixtures(
    val feeRates: FeeRates,
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

fun getFixtures(chainringChainId: ChainId) = Fixtures(
    feeRates = FeeRates.fromPercents(maker = 1.0, taker = 2.0),
    chains = listOf(
        Fixtures.Chain(chainringChainId, "chainring-dev", Address("0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc"))
    ),
    symbols = listOf(
        // BTC is the native token since we would be on Bitcoin L2
        Fixtures.Symbol(name = "BTC", chainId = chainringChainId, isNative = true, 18),
        // ETH is bridged and represented by an ERC20 token
        Fixtures.Symbol(name = "ETH", chainId = chainringChainId, isNative = false, 18),
        Fixtures.Symbol(name = "USDC", chainId = chainringChainId, isNative = false, 6),
        Fixtures.Symbol(name = "DAI", chainId = chainringChainId, isNative = false, 18)
    ),
    markets = listOf(
        Fixtures.Market(
            baseSymbol = SymbolId(chainringChainId, "BTC"),
            quoteSymbol = SymbolId(chainringChainId, "ETH"),
            tickSize = "0.05".toBigDecimal(),
            marketPrice = "17.525".toBigDecimal()
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(chainringChainId, "USDC"),
            quoteSymbol = SymbolId(chainringChainId, "DAI"),
            tickSize = "0.01".toBigDecimal(),
            marketPrice = "2.05".toBigDecimal()
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(chainringChainId, "BTC"),
            quoteSymbol = SymbolId(chainringChainId, "USDC"),
            tickSize = "1.00".toBigDecimal(),
            marketPrice = "68390.000".toBigDecimal()
        ),
    ),
    wallets = listOf(
        Fixtures.Wallet(
            privateKeyHex = "0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356",
            address = Address("0x14dC79964da2C08b23698B3D3cc7Ca32193d9955"),
            balances = mapOf(
                SymbolId(chainringChainId, "BTC") to BigDecimal("10").toFundamentalUnits(18),
                SymbolId(chainringChainId, "ETH") to BigDecimal("100").toFundamentalUnits(18),
                SymbolId(chainringChainId, "USDC") to BigDecimal("100000").toFundamentalUnits(6),
                SymbolId(chainringChainId, "DAI") to BigDecimal("100000").toFundamentalUnits(18),
            )
        ),
        Fixtures.Wallet(
            privateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
            address = Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
            balances = mapOf(
                SymbolId(chainringChainId, "BTC") to BigDecimal("10").toFundamentalUnits(18),
                SymbolId(chainringChainId, "ETH") to BigDecimal("100").toFundamentalUnits(18),
                SymbolId(chainringChainId, "USDC") to BigDecimal("100000").toFundamentalUnits(6),
                SymbolId(chainringChainId, "DAI") to BigDecimal("100000").toFundamentalUnits(18),
            )
        ),
        Fixtures.Wallet(
            privateKeyHex = "0x2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6",
            address = Address("0xa0Ee7A142d267C1f36714E4a8F75612F20a79720"),
            balances = mapOf(
                SymbolId(chainringChainId, "BTC") to BigDecimal("10").toFundamentalUnits(18),
                SymbolId(chainringChainId, "ETH") to BigDecimal("100").toFundamentalUnits(18),
                SymbolId(chainringChainId, "USDC") to BigDecimal("100000").toFundamentalUnits(6),
                SymbolId(chainringChainId, "DAI") to BigDecimal("100000").toFundamentalUnits(18),
            )
        )
    )
)

private fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}
