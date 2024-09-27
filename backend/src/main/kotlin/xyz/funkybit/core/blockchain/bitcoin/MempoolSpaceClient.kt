package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash

sealed class MempoolSpaceApi {
    @Serializable
    data class Transaction(
        @SerialName("txid")
        val txId: TxHash,
        val version: Int,
        val size: Int,
        val weight: Int,
        @SerialName("vin")
        val vins: List<VIn>,
        @SerialName("vout")
        val vouts: List<VOut>,
        val status: Status,
    ) {
        fun outputsMatchingWallet(address: BitcoinAddress) = vouts.filter { it.scriptPubKeyAddress == address }

        fun inputsMatchingWallet(address: BitcoinAddress) = vins.filter { it.prevOut.scriptPubKeyAddress == address }
    }

    @Serializable
    data class Status(
        val confirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Long?,
    )

    @Serializable
    data class VOut(
        val value: BigIntegerJson,
        @SerialName("scriptpubkey_address")
        val scriptPubKeyAddress: BitcoinAddress,
    )

    @Serializable
    data class VIn(
        @SerialName("txid")
        val txId: TxHash,
        val vout: Int,
        @SerialName("prevout")
        val prevOut: VOut,
    )

    data class MempoolApiFailure(
        val error: String,
    )

    @Serializable
    data class Stats(
        @SerialName("funded_txo_sum")
        val fundedTxoSum: Long,
        @SerialName("spent_txo_sum")
        val spentTxoSum: Long,
    )

    @Serializable
    data class AddressStats(
        val address: String,
        @SerialName("chain_stats")
        val chainStats: Stats,
        @SerialName("mempool_stats")
        val mempoolStats: Stats,
    )

    @Serializable
    data class RecommendedFees(
        val fastestFee: Int,
        val halfHourFee: Int,
        val hourFee: Int,
        val economyFee: Int,
        val minimumFee: Int,
    )
}

object MempoolSpaceClient {

    val apiServerRootUrl = System.getenv("MEMPOOL_SPACE_API_URL") ?: "http://localhost:1080/api"
    val httpClient = OkHttpClient.Builder().build()

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }
    val logger = KotlinLogging.logger {}

    private val mediaType = "text/plain".toMediaTypeOrNull()

    private val minFee = bitcoinConfig.feeSettings.minValue.toBigInteger()
    private val maxFee = bitcoinConfig.feeSettings.maxValue.toBigInteger()

    private fun execute(request: Request): Response {
        logger.debug { "Request -> ${request.method} ${request.url}" }
        return httpClient.newCall(request).execute()
    }

    fun getTransactions(walletAddress: BitcoinAddress, afterTxId: TxHash?): List<MempoolSpaceApi.Transaction> {
        val url = "$apiServerRootUrl/address/${walletAddress.value}/txs".toHttpUrl().newBuilder().apply {
            afterTxId?.let {
                addQueryParameter("after_txid", afterTxId.value)
            }
        }.build()
        return execute(
            Request.Builder()
                .url(url)
                .get()
                .build(),
        ).toPayload()
    }

    fun getTransaction(txId: TxHash): MempoolSpaceApi.Transaction? {
        val response = execute(
            Request.Builder()
                .url("$apiServerRootUrl/tx/${txId.value}".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        )
        return when (response.code) {
            404 -> null
            200 -> response.toPayload()
            else -> throw Exception("Error getting tx - ${response.code}, ${response.body}")
        }
    }

    fun sendTransaction(rawTransactionHex: String): TxHash {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/tx".toHttpUrl().newBuilder().build())
                .post(rawTransactionHex.toRequestBody(mediaType))
                .build(),
        ).toPayload()
    }

    fun getBalance(walletAddress: BitcoinAddress): Long {
        val stats: MempoolSpaceApi.AddressStats = execute(
            Request.Builder()
                .url("$apiServerRootUrl/address/${walletAddress.value}".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()
        return stats.chainStats.fundedTxoSum + stats.mempoolStats.fundedTxoSum - stats.chainStats.spentTxoSum - stats.mempoolStats.spentTxoSum
    }

    fun getRecommendedFees(): MempoolSpaceApi.RecommendedFees {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/fees/recommended".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()
    }

    fun getCurrentBlock(): Long {
        return execute(
            Request.Builder()
                .url("$apiServerRootUrl/blocks/tip/height".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()
    }

    fun calculateFee(vsize: Int) =
        maxFee.min(minFee.max(getRecommendedFees().fastestFee.toBigInteger())) * vsize.toBigInteger()

    fun estimateVSize(numIn: Int, numOut: Int): Int {
        return 11 + numIn * 63 + numOut * 41
    }

    private inline fun <reified T> Response.toPayload(): T {
        val bodyString = body?.string()
        logger.debug { "Response($code) <- $bodyString" }
        if (!isSuccessful) {
            logger.warn { "API call failed with code=$code, body=$bodyString" }
            throw Exception(bodyString ?: "Unknown Error")
        }
        return json.decodeFromString<T>(bodyString!!)
    }

    fun getNetworkFeeForTx(txId: TxHash): Long {
        return getTransaction(txId)?.let { tx ->
            (
                tx.vins.sumOf {
                    it.prevOut.value
                } - tx.vouts.sumOf {
                    it.value
                }
                ).toLong()
        } ?: throw Exception("Tx not found")
    }
}
