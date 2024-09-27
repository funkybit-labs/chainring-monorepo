package xyz.funkybit.tasks.fixtures

import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.utils.toFundamentalUnits
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
        val networkType: NetworkType
    )

    data class Symbol(
        val name: String,
        val description: String,
        val chainId: ChainId,
        val isNative: Boolean,
        val decimals: Int,
        val withdrawalFee: BigDecimal,
        val iconUrl: String = "https://chainring-web-icons.s3.us-east-2.amazonaws.com/symbols/${name.split(":")[0].lowercase()}.svg"
    ) {
        val id: SymbolId = SymbolId(chainId, name)
    }

    data class Market(
        val baseSymbol: SymbolId,
        val quoteSymbol: SymbolId,
        val tickSize: BigDecimal,
        val lastPrice: BigDecimal,
        val minFee: BigDecimal
    )

    data class Wallet(
        val privateKeyHex: String,
        val address: Address,
        val balances: Map<SymbolId, BigInteger>
    )
}

fun String.toChainSymbol(chainId: ChainId) = "$this:$chainId"

fun getFixtures(evmClients: List<BlockchainClient>) = Fixtures(
    feeRates = FeeRates.fromPercents(
        maker = System.getenv("MAKER_FEE_RATE")?.toBigDecimalOrNull() ?: BigDecimal("0.01"),
        taker = System.getenv("TAKER_FEE_RATE")?.toBigDecimalOrNull() ?: BigDecimal("0.02")
    ),
    chains = evmClients.map {
        Fixtures.Chain(
            it.chainId,
            it.config.name,
            EvmAddress("0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc"),
            it.config.url,
            it.config.blockExplorerNetName,
            it.config.blockExplorerUrl,
            NetworkType.Evm
        )
    } + if (bitcoinConfig.enabled) {
        listOf(
            Fixtures.Chain(
                bitcoinConfig.chainId,
                "Bitcoin",
                BitcoinAddress.canonicalize("bcrt1qj2w49gms5vm0ea7vhj028p355eyped59vjgufd"),
                BitcoinClient.url,
                bitcoinConfig.blockExplorerNetName,
                bitcoinConfig.blockExplorerUrl,
                NetworkType.Bitcoin
            )
        )
    } else listOf(),
    symbols = evmClients.map { client ->
        listOf(
            // BTC is the native token since we would be on Bitcoin L2
            Fixtures.Symbol(
                name = "BTC".toChainSymbol(client.chainId),
                description = "Bitcoin",
                chainId = client.chainId,
                isNative = true,
                decimals = 18,
                withdrawalFee = BigDecimal("0.00002")
            ),
            // ETH is bridged and represented by an ERC20 token
            Fixtures.Symbol(
                name = "ETH".toChainSymbol(client.chainId),
                description = "Ethereum",
                chainId = client.chainId,
                isNative = false,
                decimals = 18,
                withdrawalFee = BigDecimal("0.0003")
            ),
            Fixtures.Symbol(
                name = "USDC".toChainSymbol(client.chainId),
                description = "USD Coin",
                chainId = client.chainId,
                isNative = false,
                decimals = 6,
                withdrawalFee = BigDecimal("1")
            ),
            Fixtures.Symbol(
                name = "DAI".toChainSymbol(client.chainId),
                description = "Dai",
                chainId = client.chainId,
                isNative = false,
                decimals = 18,
                withdrawalFee = BigDecimal("1")
            )
        )
    }.flatten() + if (bitcoinConfig.enabled) {
        listOf(
            Fixtures.Symbol(
                name = "BTC".toChainSymbol(bitcoinConfig.chainId),
                description = "Bitcoin",
                chainId = bitcoinConfig.chainId,
                isNative = true,
                decimals = 8,
                withdrawalFee = BigDecimal("0.00000002")
            )
        )
    } else listOf(),
    markets = evmClients.map { client ->
        listOf(
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "BTC"),
                quoteSymbol = SymbolId(client.chainId, "ETH"),
                tickSize = "0.05".toBigDecimal(),
                lastPrice = "17.525".toBigDecimal(),
                minFee = BigDecimal("0.00001")
            ),
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "USDC"),
                quoteSymbol = SymbolId(client.chainId, "DAI"),
                tickSize = "0.01".toBigDecimal(),
                lastPrice = "1.005".toBigDecimal(),
                minFee = BigDecimal("0.02")
            ),
            Fixtures.Market(
                baseSymbol = SymbolId(client.chainId, "BTC"),
                quoteSymbol = SymbolId(client.chainId, "USDC"),
                tickSize = "25.00".toBigDecimal(),
                lastPrice = "60812.500".toBigDecimal(),
                minFee = BigDecimal("0.02")
            ),
        )
    }.flatten() + (if (evmClients.size > 1) listOf(
        Fixtures.Market(
            baseSymbol = SymbolId(evmClients[0].chainId, "BTC"),
            quoteSymbol = SymbolId(evmClients[1].chainId, "BTC"),
            tickSize = "0.001".toBigDecimal(),
            lastPrice = "1.0005".toBigDecimal(),
            minFee = BigDecimal("0.000005")
        ),
        Fixtures.Market(
            baseSymbol = SymbolId(evmClients[0].chainId, "BTC"),
            quoteSymbol = SymbolId(evmClients[1].chainId, "ETH"),
            tickSize = "0.05".toBigDecimal(),
            lastPrice = "17.525".toBigDecimal(),
            BigDecimal("0.00001")
        ),
    ) else emptyList()) + if (bitcoinConfig.enabled) {
        listOf(
                Fixtures.Market(
                    baseSymbol = SymbolId(evmClients[0].chainId, "BTC"),
                    quoteSymbol = SymbolId(bitcoinConfig.chainId, "BTC"),
                    tickSize = "0.001".toBigDecimal(),
                    lastPrice = "1.0005".toBigDecimal(),
                    BigDecimal("0.00000001")
                ),
            )
    } else listOf(),
    wallets = listOf(
        Fixtures.Wallet(
            privateKeyHex = "0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356",
            address = EvmAddress("0x14dC79964da2C08b23698B3D3cc7Ca32193d9955"),
            balances = evmClients.mapIndexed { index, client ->
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
            address = EvmAddress("0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f"),
            balances = evmClients.mapIndexed { index, client ->
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
            address = EvmAddress("0xa0Ee7A142d267C1f36714E4a8F75612F20a79720"),
            balances = evmClients.mapIndexed { index, client ->
                mapOf(
                    SymbolId(client.chainId, "BTC") to BigDecimal("10$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "ETH") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "USDC") to BigDecimal("100000$index").toFundamentalUnits(6),
                    SymbolId(client.chainId, "DAI") to BigDecimal("100000$index").toFundamentalUnits(18),
                )
            }.flatMap { map -> map.entries }.associate(Map.Entry<SymbolId, BigInteger>::toPair)
        ),
        Fixtures.Wallet(
            // Seed phrase: fine knee similar divert school child welcome oval spin jump merit accuse
            privateKeyHex = "0x80a90a34f3ee81dd90ac44f706a4dbc7c0a7ca35d0ab698149b92f8a13388cdd",
            address = BitcoinAddress.canonicalize("2MxLtfRKbHshBsqmPn5qSniiqMD7e1jDyJK"),
            balances = mapOf(
                SymbolId(bitcoinConfig.chainId, "BTC") to if (bitcoinConfig.enabled) BigDecimal("0.2").toFundamentalUnits(8) else BigInteger.ZERO,
            )
        ),
        // airdropper
        Fixtures.Wallet(
            privateKeyHex = "0xc664badcbc1824995c98407e26667e35c648312061a2de44569851f79b0a5371",
            address = EvmAddress("0x831703d43B7BaF132ff6a608022CA66Cd9A12aCc"),
            balances = evmClients.mapIndexed { index, client ->
                mapOf(
                    SymbolId(client.chainId, "BTC") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "ETH") to BigDecimal("100$index").toFundamentalUnits(18),
                    SymbolId(client.chainId, "USDC") to BigDecimal("100000$index").toFundamentalUnits(6),
                )
            }.flatMap { map -> map.entries }.associate(Map.Entry<SymbolId, BigInteger>::toPair)
        ),
        // faucet
        Fixtures.Wallet(
            privateKeyHex = "0x497a24f8d565f1776e7c943e1607d735181d1fc21007fb69dabac1e1e7a641a0",
            address = EvmAddress("0x52617B854f7f98D54D121852689b4155033531Ab"),
            balances = evmClients.mapIndexed { index, client ->
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
