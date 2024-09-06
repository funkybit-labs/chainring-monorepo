package xyz.funkybit.core.utils

import aws.smithy.kotlin.runtime.text.encoding.encodeBase64String
import org.apache.commons.codec.binary.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.funkybit.core.utils.IconUtils.sanitizeImageUrl

class TestIconUtils {

    @Test
    fun `test sanitize image url`() {
        assertEquals(IconUtils.SanitizeFailure.TooLarge, sanitizeImageUrl("01234567".repeat(1024 * 256) + "1").leftOrNull())
        assertEquals(IconUtils.SanitizeFailure.MalformedUrl, sanitizeImageUrl("not-a-url").leftOrNull())
        assertEquals(IconUtils.SanitizeFailure.InvalidProtocol, sanitizeImageUrl("file:///foo/bar.com").leftOrNull())
        assertEquals(IconUtils.SanitizeFailure.InvalidExtension, sanitizeImageUrl("https://foo/bar.pbm").leftOrNull())
        assertEquals("https://foo/bar.PNG", sanitizeImageUrl("https://foo/bar.PNG").getOrNull())
        assertEquals(IconUtils.SanitizeFailure.MalformedDataUrl, sanitizeImageUrl("data:some-nonsense").leftOrNull())
        assertEquals(IconUtils.SanitizeFailure.MalformedDataUrl, sanitizeImageUrl("data:image/pbm;base64,asldjfh").leftOrNull())
        assertEquals(IconUtils.SanitizeFailure.InvalidSvgContent, sanitizeImageUrl("data:image/svg+xml;base64,asldjfh").leftOrNull())
        val svgContent = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
          <circle cx="50" cy="50" r="40" fill="red" />
        </svg>
        """.trimIndent()
        val expectedSanitizedSvgContent = """
<svg contentScriptType="text/ecmascript"
     xmlns:xlink="http://www.w3.org/1999/xlink" zoomAndPan="magnify"
     contentStyleType="text/css" viewBox="0 0 100 100"
     preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg"
     version="1.0">
    <circle fill="red" r="40" cx="50" cy="50"/>
</svg>"""
        val sanitizedUrl = sanitizeImageUrl("data:image/svg+xml;base64,${svgContent.toByteArray().encodeBase64String()}").getOrNull()
        val dataUrlRegex = Regex("^data:([^;]*);(base64,)?(.*)$")
        val match = dataUrlRegex.matchEntire(sanitizedUrl!!)!!
        val sanitizedSvgContent = Base64.decodeBase64(match.destructured.component3()).decodeToString()
        assertEquals(expectedSanitizedSvgContent, sanitizedSvgContent)

        val svgContentWithScriptAndEventHandlers = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
          <circle cx="50" cy="50" r="40" fill="red" onClick="javascript:doEvil()" /><script>(function doEvil(){})()</script>
        </svg>
        """.trimIndent()
        val sanitizedUrl2 = sanitizeImageUrl("data:image/svg+xml;base64,${svgContentWithScriptAndEventHandlers.toByteArray().encodeBase64String()}").getOrNull()
        val match2 = dataUrlRegex.matchEntire(sanitizedUrl2!!)!!
        val sanitizedSvgContent2 = Base64.decodeBase64(match2.destructured.component3()).decodeToString()
        assertEquals(expectedSanitizedSvgContent, sanitizedSvgContent2)
    }
}
