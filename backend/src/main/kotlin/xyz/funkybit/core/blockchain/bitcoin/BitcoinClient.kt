package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.SmartFeeMode
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.rpc.BitcoinRpc
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcRequest
import xyz.funkybit.core.utils.bitcoin.inSatsAsDecimalString
import xyz.funkybit.core.utils.toFundamentalUnits
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

    val config = ChainManager.bitcoinBlockchainClientConfig
    val chainId = ChainId(0u)
    private val minFee = BigDecimal(config.feeSettings.minValue)
    private val maxFee = BigDecimal(config.feeSettings.maxValue)

    val zeroCoinValue = Coin.valueOf(0)

    fun getParams(): NetworkParameters = NetworkParameters.fromID(ChainManager.bitcoinBlockchainClientConfig.net)!!

    fun mine(nBlocks: Int): List<String> {
        return getValue(
            BitcoinRpcRequest(
                "generatetoaddress",
                BitcoinRpcParams(listOf(nBlocks, config.faucetAddress)),
            ),
        )
    }

    fun getBlockCount(): Long {
        return getValue(
            BitcoinRpcRequest(
                "getblockcount",
            ),
        )
    }

    fun getBlockHash(blockHeight: Long): String {
        return getValue(
            BitcoinRpcRequest(
                "getblockhash",
                BitcoinRpcParams(listOf(blockHeight)),
            ),
        )
    }

    fun getBlock(blockhash: String, verbosity: Int = 2): BitcoinRpc.Block {
        return getValue(
            BitcoinRpcRequest(
                "getblock",
                BitcoinRpcParams(listOf(blockhash, verbosity)),
            ),
            true,
        )
    }

    fun getBlockHeader(blockhash: String): BitcoinRpc.Block {
        return getValue(
            BitcoinRpcRequest(
                "getblockheader",
                BitcoinRpcParams(listOf(blockhash)),
            ),
            true,
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

    fun getRawTransaction(txId: TxHash): BitcoinRpc.Transaction? {
        return try {
            getValue(
                BitcoinRpcRequest(
                    "getrawtransaction",
                    BitcoinRpcParams(listOf(txId.value, true)),
                ),
                true,
            )
        } catch (e: JsonRpcException) {
            if (!e.error.message.contains("No such mempool or blockchain transaction")) {
                throw e
            }
            null
        }
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
        return estimateSmartFee(config.feeSettings.blocks, config.feeSettings.mode).feeRate?.let {
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

    fun buildAndSignDepositTx(
        accountAddress: BitcoinAddress,
        amount: BigInteger,
        utxos: List<BitcoinUtxoEntity>,
        ecKey: ECKey,
    ): Transaction {
        val params = getParams()
        val feeAmount = estimateDepositTxFee(ecKey, accountAddress, utxos)
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                Coin.valueOf(amount.toLong()),
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - amount - feeAmount)
        if (changeAmount > config.changeDustThreshold) {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
                ),
            )
        }
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.vout(),
                    Sha256Hash.wrap(it.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return rawTx
    }

    private fun estimateDepositTxFee(
        ecKey: ECKey,
        accountAddress: BitcoinAddress,
        utxos: List<BitcoinUtxoEntity>,
    ): BigInteger {
        val params = getParams()
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
            ),
        )
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.vout(),
                    Sha256Hash.wrap(it.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return calculateFee(rawTx.vsize)
    }

    fun calculateFee(vsize: Int) =
        estimateSmartFeeInSatPerVByte().toBigInteger() * vsize.toBigInteger()

    fun estimateVSize(numIn: Int, numOut: Int): Int {
        return 11 + numIn * 63 + numOut * 41
    }

    fun getNetworkFeeForTx(txId: TxHash): Long {
        return getRawTransaction(txId)?.let { tx ->
            (
                tx.txIns.sumOf {
                    getRawTransaction(it.txId!!)!!.txOuts[it.outIndex!!].value.toFundamentalUnits(8)
                } - tx.txOuts.sumOf {
                    it.value.toFundamentalUnits(8)
                }
                ).toLong()
        } ?: throw Exception("Tx not found")
    }

    fun getSourceAddress(tx: BitcoinRpc.Transaction): BitcoinAddress? {
        return try {
            if (tx.txIns.isNotEmpty() && tx.txIns[0].txId != null && tx.txIns[0].outIndex != null) {
                getRawTransaction(tx.txIns[0].txId!!)?.txOuts?.firstOrNull {
                    it.index == tx.txIns[0].outIndex
                }?.let {
                    it.scriptPubKey.addresses?.firstOrNull() ?: it.scriptPubKey.address
                }?.let(BitcoinAddress.Companion::canonicalize)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Unable to get source address" }
            null
        }
    }
}
