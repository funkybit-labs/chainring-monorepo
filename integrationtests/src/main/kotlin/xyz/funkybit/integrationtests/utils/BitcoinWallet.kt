package xyz.funkybit.integrationtests.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.Chain
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.DepositApiResponse
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.Signature
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.bitcoin.fromSatoshi
import java.math.BigInteger

class BitcoinWallet(
    val keyPair: WalletKeyPair.Bitcoin,
    val chain: Chain,
    val apiClient: ApiClient,
) {
    val logger = KotlinLogging.logger {}
    companion object {
        operator fun invoke(apiClient: ApiClient): BitcoinWallet {
            val config = apiClient.getConfiguration().bitcoinChain
            return BitcoinWallet(apiClient.keyPair as WalletKeyPair.Bitcoin, config, apiClient)
        }
    }

    val walletAddress = keyPair.address()
    val exchangeDepositAddress = chain.contracts.first { it.name == ContractType.Exchange.name }.address as BitcoinAddress
    val nativeSymbol = chain.symbols.first { it.contractAddress == null }

    fun getWalletNativeBalance(): BigInteger {
        return MempoolSpaceClient.getBalance(walletAddress).toBigInteger()
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

    private fun sendNativeDepositTx(amount: BigInteger): TxHash {
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
                UtxoSelectionService.reserveUtxos(walletAddress, selectedUtxos.map { it.utxoId }.toSet(), txId.value)
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
}
