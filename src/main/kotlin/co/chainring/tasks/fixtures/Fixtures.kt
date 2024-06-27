package co.chainring.tasks.fixtures

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.model.Address
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
        val submitterAddress: Address,
        val jsonRpcUrl: String,
        val blockExplorerNetName: String,
        val blockExplorerUrl: String,
    )

    data class Symbol(
        val name: String,
        val description: String,
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

fun String.toChainSymbol(chainId: ChainId) = "$this:$chainId"

fun getFixtures(chainringChainClients: List<BlockchainClient>) = Fixtures(
    feeRates = FeeRates.fromPercents(maker = System.getenv("MAKER_FEE_RATE")?.toDoubleOrNull() ?: 1.0, taker = System.getenv("TAKER_FEE_RATE")?.toDoubleOrNull() ?: 2.0),
    chains = chainringChainClients.map {
        Fixtures.Chain(it.chainId, it.config.name, Address("0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc"), it.config.url, it.config.blockExplorerNetName, it.config.blockExplorerUrl)
    },
    symbols = chainringChainClients.map { client ->
        listOf(
            // BTC is the native token since we would be on Bitcoin L2
            Fixtures.Symbol(name = "BTC".toChainSymbol(client.chainId), description = "Bitcoin", chainId = client.chainId, isNative = true, 18),
            // ETH is bridged and represented by an ERC20 token
            Fixtures.Symbol(name = "ETH".toChainSymbol(client.chainId), description = "Ethereum", chainId = client.chainId, isNative = false, 18),
            Fixtures.Symbol(name = "USDC".toChainSymbol(client.chainId), description = "USD Coin", chainId = client.chainId, isNative = false, 6),
            Fixtures.Symbol(name = "DAI".toChainSymbol(client.chainId), description = "Dai", chainId = client.chainId, isNative = false, 18)
        )
    }.flatten(),
    markets = chainringChainClients.map { client ->
        listOf(
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "BTC"),
                quoteSymbol = SymbolId(client.chainId, "ETH"),
                tickSize = "0.05".toBigDecimal(),
                marketPrice = "17.525".toBigDecimal()
            ),
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "USDC"),
                quoteSymbol = SymbolId(client.chainId, "DAI"),
                tickSize = "0.01".toBigDecimal(),
                marketPrice = "2.005".toBigDecimal()
            ),
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "BTC"),
                quoteSymbol = SymbolId(client.chainId, "USDC"),
                tickSize = "25.00".toBigDecimal(),
                marketPrice = "60812.500".toBigDecimal()
            ),
        )
    }.flatten() + if (chainringChainClients.size > 1) listOf(
        Fixtures.Market(
            baseSymbol = SymbolId(chainringChainClients[0].chainId, "BTC"),
            quoteSymbol = SymbolId(chainringChainClients[1].chainId, "BTC"),
            tickSize = "0.001".toBigDecimal(),
            marketPrice = "1.0005".toBigDecimal()
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(chainringChainClients[0].chainId, "BTC"),
            quoteSymbol = SymbolId(chainringChainClients[1].chainId, "ETH"),
            tickSize = "0.05".toBigDecimal(),
            marketPrice = "17.525".toBigDecimal()
        ),
    ) else emptyList(),
    wallets = listOf(
        Fixtures.Wallet(
            privateKeyHex = "0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356",
            address = Address("0x14dC79964da2C08b23698B3D3cc7Ca32193d9955"),
            balances = chainringChainClients.mapIndexed { index, client ->
                mapOf(
                    SymbolId(client.chainId, "BTC") to BigDecimal("10$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "ETH") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "USDC") to BigDecimal("100000$index").toFundamentalUnits(6),
                    SymbolId(client.chainId, "DAI") to BigDecimal("100000$index").toFundamentalUnits(18),
                )
            }.flatMap { map -> map.entries }.associate(Map.Entry<SymbolId, BigInteger>::toPair)
        ),
        Fixtures.Wallet(
            privateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97",
            address = Address("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
            balances = chainringChainClients.mapIndexed { index, client ->
                mapOf(
                    SymbolId(client.chainId, "BTC") to BigDecimal("10$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "ETH") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "USDC") to BigDecimal("100000$index").toFundamentalUnits(6),
                    SymbolId(client.chainId, "DAI") to BigDecimal("100000$index").toFundamentalUnits(18),
                )
            }.flatMap { map -> map.entries }.associate(Map.Entry<SymbolId, BigInteger>::toPair)
        ),
        Fixtures.Wallet(
            privateKeyHex = "0x2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6",
            address = Address("0xa0Ee7A142d267C1f36714E4a8F75612F20a79720"),
            balances = chainringChainClients.mapIndexed { index, client ->
                mapOf(
                    SymbolId(client.chainId, "BTC") to BigDecimal("10$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "ETH") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "USDC") to BigDecimal("100000$index").toFundamentalUnits(6),
                    SymbolId(client.chainId, "DAI") to BigDecimal("100000$index").toFundamentalUnits(18),
                )
            }.flatMap { map -> map.entries }.associate(Map.Entry<SymbolId, BigInteger>::toPair)
        )
    )
)

private fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}
