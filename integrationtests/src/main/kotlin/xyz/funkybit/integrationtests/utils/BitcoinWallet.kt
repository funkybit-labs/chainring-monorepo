package xyz.funkybit.integrationtests.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.Chain
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.DepositApiResponse
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.Signature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.bitcoin.fromSatoshi
import xyz.funkybit.core.utils.fromFundamentalUnits
import java.math.BigInteger

class BitcoinWallet(
    val keyPair: WalletKeyPair.Bitcoin,
    val allChains: List<Chain>,
    val apiClient: ApiClient,
) : OrderSigner {
    val logger = KotlinLogging.logger {}
    companion object {
        operator fun invoke(apiClient: ApiClient): BitcoinWallet {
            val config = apiClient.getConfiguration().chains
            return BitcoinWallet(apiClient.keyPair as WalletKeyPair.Bitcoin, config, apiClient)
        }
    }

    val chain = allChains.first { it.networkType == NetworkType.Bitcoin }
    val walletAddress = keyPair.address().also {
        transaction { BitcoinUtxoAddressMonitorEntity.createIfNotExists(it) }
    }
    val exchangeDepositAddress = chain.contracts.first { it.name == ContractType.Exchange.name }.address as BitcoinAddress
    val nativeSymbol = chain.symbols.first { it.contractAddress == null }

    fun getWalletNativeBalance(): BigInteger {
        return transaction {
            BitcoinUtxoEntity.findUnspentTotal(walletAddress)
        }
    }

    fun depositNative(amount: BigInteger): DepositApiResponse {
        return apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = Symbol(nativeSymbol.name),
                amount = amount,
                txHash = xyz.funkybit.core.model.TxHash.fromDbModel(sendNativeDepositTx(amount)),
            ),
        )
    }

    fun getExchangeBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            if (symbol.contractAddress == null) {
                getExchangeNativeBalance(symbol.name)
            } else {
                BigInteger.ZERO
            }.fromFundamentalUnits(symbol.decimals),
        )

    private fun getExchangeNativeBalance(symbol: String): BigInteger {
        return transaction {
            val symbolEntity = SymbolEntity.forName(symbol)
            ArchAccountBalanceIndexEntity.findForWalletAddressAndSymbol(walletAddress, symbolEntity)?.let { archAccountBalanceIndexEntity ->
                val tokenAccountPubKey = archAccountBalanceIndexEntity.archAccount.rpcPubkey()
                val addressIndex = archAccountBalanceIndexEntity.addressIndex
                val tokenState = ArchUtils.getAccountState<ArchAccountState.Token>(tokenAccountPubKey)
                assert(tokenState.balances[addressIndex].walletAddress == walletAddress.value)
                tokenState.balances[addressIndex].balance.toLong().toBigInteger()
            } ?: BigInteger.ZERO
        }
    }

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    allChains.map { it.symbols.filter { s -> s.name == base } }.flatten().first(),
                    allChains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }

    fun sendNativeDepositTx(amount: BigInteger): TxHash {
        return transaction {
            val selectedUtxos = UtxoSelectionService.selectUtxos(
                walletAddress,
                amount,
                BitcoinClient.calculateFee(BitcoinClient.estimateVSize(1, 2)),
            )

            val depositTx = BitcoinClient.buildAndSignDepositTx(
                exchangeDepositAddress,
                amount,
                selectedUtxos,
                keyPair.ecKey,
            )

            BitcoinClient.sendRawTransaction(depositTx.toHexString()).also { txId ->
                UtxoSelectionService.reserveUtxos(selectedUtxos, txId.value)
            }
        }
    }

    fun airdropNative(amount: BigInteger): TxHash {
        return BitcoinClient.sendToAddress(
            walletAddress,
            amount,
        )
    }

    private fun getRawTx(txId: TxHash) = BitcoinClient.getRawTransaction(txId)

    fun signWithdraw(symbol: String, amount: BigInteger): CreateWithdrawalApiRequest {
        val nonce = System.currentTimeMillis()
        val message = "[funkybit] Please sign this message to authorize withdrawal of ${amount.fromSatoshi().toPlainString()} ${symbol.replace(Regex(":.*"), "")} from the exchange to your wallet."
        val bitcoinLinkAddressMessage = "$message\nAddress: ${walletAddress.value}, Timestamp: ${Instant.fromEpochMilliseconds(nonce)}"
        val signature = keyPair.ecKey.signMessage(bitcoinLinkAddressMessage)
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            Signature.auto(signature),
        )
    }

    override fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        return request.copy(
            signature = sign(request),
            verifyingChainId = chain.id,
        )
    }

    override fun signOrder(request: CreateOrderApiRequest.Limit, linkedSignerKeyPair: WalletKeyPair?): CreateOrderApiRequest.Limit {
        return request.copy(
            signature = sign(request),
            verifyingChainId = chain.id,
        )
    }

    override fun signOrder(request: CreateOrderApiRequest.BackToBackMarket): CreateOrderApiRequest.BackToBackMarket {
        return request.copy(
            signature = sign(request),
            verifyingChainId = chain.id,
        )
    }

    private fun sign(request: CreateOrderApiRequest): Signature {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)
        val baseSymbolName = baseSymbol.name.replace(Regex(":.*"), "")
        val quoteSymbolName = quoteSymbol.name.replace(Regex(":.*"), "")

        val bitcoinAddress = BitcoinAddress.canonicalize(walletAddress.value)
        val amount = when (request.amount) {
            is OrderAmount.Fixed -> request.amount.fixedAmount().fromFundamentalUnits(baseSymbol.decimals).toPlainString()
            is OrderAmount.Percent -> "${request.amount.percentage()}% of your "
        }
        val bitcoinOrderMessage = "[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees." +
            if (request.side == OrderSide.Buy) {
                "\nSwap $amount $quoteSymbolName for $baseSymbolName"
            } else {
                "\nSwap $amount $baseSymbolName for $quoteSymbolName"
            } + when (request) {
                is CreateOrderApiRequest.Limit -> "\nPrice: ${request.price.toPlainString()}"
                else -> "\nPrice: Market"
            } + "\nAddress: ${bitcoinAddress.value}, Nonce: ${request.nonce}"
        logger.debug { "wallet - message to sign = [$bitcoinOrderMessage" }
        return Signature.auto(keyPair.ecKey.signMessage(bitcoinOrderMessage))
    }
}
