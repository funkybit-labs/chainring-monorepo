package xyz.funkybit.core.utils

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import org.apache.commons.codec.binary.Base64
import java.util.UUID

object IconUtils {
    private val BUCKET = System.getenv("WEB_ICON_BUCKET") ?: "funkybit-web-icons"
    private val REGION = System.getenv("WEB_ICON_REGION") ?: "us-east-2"
    suspend fun resolveSymbolUrl(url: String): String {
        return Regex("^data:([^;]*);(base64,)?(.*)$").matchEntire(url)?.let { match ->
            val(mimeType, base64, content) = match.destructured
            val extension = when (mimeType) {
                "image/svg+xml" -> "svg"
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> "unknown"
            }
            val path = "symbols/${UUID.randomUUID()}.$extension"
            S3Client.fromEnvironment {
                region = REGION
            }.let { s3 ->
                s3.putObject(
                    PutObjectRequest {
                        bucket = BUCKET
                        key = path
                        body = if (base64 == "") ByteStream.fromString(content) else ByteStream.fromBytes(Base64.decodeBase64(content))
                        contentType = mimeType
                    },
                )
            }
            "https://$BUCKET.s3.$REGION.amazonaws.com/$path"
        } ?: url
    }
}
