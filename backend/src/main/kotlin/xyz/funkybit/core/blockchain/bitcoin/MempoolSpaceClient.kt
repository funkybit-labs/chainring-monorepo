package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import xyz.funkybit.apps.api.model.BigIntegerJson
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.TxHash

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
    )

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
}

object MempoolSpaceClient {

    val apiServerRootUrl = System.getenv("MEMPOOL_SPACE_API_URL") ?: "https://mempool.dev.aws.archnetwork.xyz/api"
    val httpClient = OkHttpClient.Builder().build()

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }
    val logger = KotlinLogging.logger {}

    private fun execute(request: Request): Response {
        logger.debug { "Request -> ${request.url}" }
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

    fun getBalance(walletAddress: BitcoinAddress): Long {
        val url = "$apiServerRootUrl/address/${walletAddress.value}".toHttpUrl().newBuilder().build()
        val stats: MempoolSpaceApi.AddressStats = execute(
            Request.Builder()
                .url(url)
                .get()
                .build(),
        ).toPayload()
        return stats.chainStats.fundedTxoSum + stats.mempoolStats.fundedTxoSum - stats.chainStats.spentTxoSum - stats.mempoolStats.spentTxoSum
    }

    private inline fun <reified T> Response.toPayload(): T {
        val bodyString = body?.string()
        logger.debug { "Response <- $bodyString" }
        if (!isSuccessful) {
            throw Exception(bodyString ?: "Unknown Error")
        }
        return json.decodeFromString<T>(bodyString!!)
    }
}
