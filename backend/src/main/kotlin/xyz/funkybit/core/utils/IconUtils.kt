package xyz.funkybit.core.utils

import arrow.core.Either
import arrow.core.raise.either
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.text.encoding.encodeBase64String
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.svg2svg.SVGTranscoder
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.commons.codec.binary.Base64
import java.io.StringReader
import java.io.StringWriter
import java.net.URL
import java.util.UUID

const val MAX_DATA_URL_LENGTH = 2 * 1024 * 1024

object IconUtils {
    private val BUCKET = System.getenv("WEB_ICON_BUCKET") ?: "chainring-web-icons"
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

    enum class SanitizeFailure {
        TooLarge,
        MalformedUrl,
        MalformedDataUrl,
        MalformedBase64,
        InvalidProtocol,
        InvalidExtension,
        InvalidSvgContent,
    }

    fun sanitizeImageUrl(url: String): Either<SanitizeFailure, String> {
        return either {
            if (url.length > MAX_DATA_URL_LENGTH) {
                raise(SanitizeFailure.TooLarge)
            } else {
                if (url.startsWith(("data:"))) {
                    val dataUrlRegex = Regex("^data:image/(jpeg|png|gif|webp|svg\\+xml);base64,([a-zA-Z0-9+/]+=*)$")
                    val match = dataUrlRegex.matchEntire(url)
                    if (match == null) {
                        raise(SanitizeFailure.MalformedDataUrl)
                    } else {
                        val (mimeType, base64Data) = match.destructured
                        try {
                            val decodedData = Base64.decodeBase64(base64Data)

                            if (mimeType == "svg+xml") {
                                // For SVG data URLs, we should sanitize the content
                                sanitizeSvgContent(decodedData.decodeToString())?.let {
                                    "data:image/svg+xml;base64,${it.toByteArray().encodeBase64String()}"
                                } ?: raise(SanitizeFailure.InvalidSvgContent)
                            } else {
                                url
                            }
                        } catch (e: IllegalArgumentException) {
                            raise(SanitizeFailure.MalformedBase64)
                        }
                    }
                } else {
                    runCatching { URL(url) }.getOrNull()?.let { parsed ->
                        if (listOf("http", "https").contains(parsed.protocol)) {
                            val allowedExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
                            if (!allowedExtensions.any { parsed.path.lowercase().endsWith(it) }) {
                                raise(SanitizeFailure.InvalidExtension)
                            }

                            parsed.toString()
                        } else {
                            raise(SanitizeFailure.InvalidProtocol)
                        }
                    } ?: raise(SanitizeFailure.MalformedUrl)
                }
            }
        }
    }

    private fun sanitizeSvgContent(svgContent: String): String? {
        return runCatching {
            val parser = XMLResourceDescriptor.getXMLParserClassName()
            val factory = SAXSVGDocumentFactory(parser)

            // Parse the SVG content
            val document = factory.createDocument(null, StringReader(svgContent))
            removeScriptsAndEvents(document.documentElement)

            // Create a transcoder
            val transcoder = SVGTranscoder()

            // Perform the transcoding
            val input = TranscoderInput(document)
            val writer = StringWriter()
            val output = TranscoderOutput(writer)
            transcoder.transcode(input, output)

            return writer.toString()
        }.getOrNull()
    }

    private fun removeScriptsAndEvents(element: org.w3c.dom.Element) {
        // Remove script elements
        val scripts = element.getElementsByTagName("script")
        while (scripts.length > 0) {
            scripts.item(0).parentNode.removeChild(scripts.item(0))
        }

        // Remove event attributes
        val attributes = (0 until element.attributes.length).map { element.attributes.item(it) }.toList()
        attributes.forEach { attr ->
            if (attr.nodeName.startsWith("on")) {
                element.removeAttributeNode(attr as org.w3c.dom.Attr)
            }
        }

        // Recursively process child elements
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is org.w3c.dom.Element) {
                removeScriptsAndEvents(child)
            }
        }
    }
}
