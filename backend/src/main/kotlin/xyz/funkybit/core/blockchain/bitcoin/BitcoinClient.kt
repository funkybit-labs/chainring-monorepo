package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.SmartFeeMode
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.rpc.BitcoinRpc
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcRequest
import xyz.funkybit.core.utils.bitcoin.inSatsAsDecimalString
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object BitcoinClient : JsonRpcClientBase(
    ChainManager.bitcoinBlockchainClientConfig.url,
    if (ChainManager.bitcoinBlockchainClientConfig.enableBasicAuth) {
        BasicAuthInterceptor(ChainManager.bitcoinBlockchainClientConfig.user, ChainManager.bitcoinBlockchainClientConfig.password)
    } else {
        null
    },
) {

    override val logger = KotlinLogging.logger {}

    val bitcoinConfig = ChainManager.bitcoinBlockchainClientConfig
    val chainId = ChainId(8086u)
    private val minFee = BigDecimal(bitcoinConfig.feeSettings.minValue)
    private val maxFee = BigDecimal(bitcoinConfig.feeSettings.maxValue)

    fun getParams(): NetworkParameters = NetworkParameters.fromID(ChainManager.bitcoinBlockchainClientConfig.net)!!

    fun mine(nBlocks: Int): List<String> {
        return getValue(
            BitcoinRpcRequest(
                "generatetoaddress",
                BitcoinRpcParams(listOf(nBlocks, bitcoinConfig.faucetAddress)),
            ),
        )
    }

    fun sendToAddress(address: BitcoinAddress, amount: BigInteger): TxHash {
        return getValue(
            BitcoinRpcRequest(
                "sendtoaddress",
                BitcoinRpcParams(listOf(address.value, amount.inSatsAsDecimalString())),
            ),
        )
    }

    fun sendToAddressAndMine(address: BitcoinAddress, amount: BigInteger): TxHash {
        return sendToAddress(address, amount).also {
            mine(1)
        }
    }

    fun getRawTransaction(txId: TxHash): BitcoinRpc.Transaction {
        return getValue(
            BitcoinRpcRequest(
                "getrawtransaction",
                BitcoinRpcParams(listOf(txId.value, true)),
            ),
            true,
        )
    }

    fun sendRawTransaction(rawTransactionHex: String): TxHash {
        return getValue(
            BitcoinRpcRequest(
                "sendrawtransaction",
                BitcoinRpcParams(listOf(rawTransactionHex)),
            ),
            true,
        )
    }

    fun estimateSmartFeeInSatPerVByte(): BigDecimal {
        return estimateSmartFee(bitcoinConfig.feeSettings.blocks, bitcoinConfig.feeSettings.mode).feeRate?.let {
            maxFee.min(minFee.max(scaleToSatoshiPerVByte(it)))
        } ?: minFee
    }

    private fun scaleToSatoshiPerVByte(btcPerVirtualKByte: BigDecimal) =
        (btcPerVirtualKByte.setScale(8) * BigDecimal("100000000") / BigDecimal("1024")).setScale(0, RoundingMode.UP)

    private fun estimateSmartFee(confBlocks: Int, smartFeeMode: SmartFeeMode?): BitcoinRpc.SmartFeeInfo {
        return getValue(
            BitcoinRpcRequest(
                "estimatesmartfee",
                BitcoinRpcParams(listOfNotNull(confBlocks, smartFeeMode?.name)),
            ),
            true,
        )
    }

    inline fun <reified T> getValue(request: BitcoinRpcRequest, logResponseBody: Boolean = true): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }
}
