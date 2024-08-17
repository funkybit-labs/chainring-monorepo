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

sealed class MempoolSpaceApi {
    @Serializable
    data class Transaction(
        @SerialName("txid")
        val txId: String,
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
        val scriptPubKeyAddress: String,
    )

    @Serializable
    data class VIn(
        @SerialName("txid")
        val txId: String,
        val vout: Int,
        @SerialName("prevout")
        val prevOut: VOut,
    )

    data class MempoolApiFailure(
        val error: String,
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

    private fun execute(request: Request): Response =
        httpClient.newCall(request).execute()

    fun getTransactions(walletAddress: String, afterTxId: String?): List<MempoolSpaceApi.Transaction> {
        val url = "$apiServerRootUrl/address/$walletAddress/txs".toHttpUrl().newBuilder().apply {
            afterTxId?.let {
                addQueryParameter("after_txid", afterTxId)
            }
        }.build()
        logger.debug { "Request -> $url" }
        return execute(
            Request.Builder()
                .url(url)
                .get()
                .build(),
        ).toPayload()
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
