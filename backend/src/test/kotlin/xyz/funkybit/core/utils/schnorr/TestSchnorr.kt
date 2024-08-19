package xyz.funkybit.core.utils.schnorr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TestSchnorr {

    @ParameterizedTest(name = "{0} - {7}")
    @CsvFileSource(files = ["src/test/resources/test-vectors.csv"], numLinesToSkip = 1)
    fun `test bip340 vectors`(index: Int, secKeyHex: String?, pubKeyHex: String, auxRandHex: String?, msgHex: String?, sigHex: String, resultString: String, comment: String?) {
        val pubKey = pubKeyHex.toHexBytes()
        val msg = msgHex?.toHexBytes() ?: ByteArray(0)
        val sig = sigHex.toHexBytes()
        val result = resultString == "TRUE"
        if (!secKeyHex.isNullOrBlank()) {
            val secKey = secKeyHex.toHexBytes()
            val pubKeyActual = Point.genPubKey(secKey)
            assertEquals(pubKey.contentToString(), pubKeyActual.contentToString())

            val auxRand = auxRandHex!!.toHexBytes()
            val sigActual = Schnorr.sign(msg, secKey, auxRand)
            assertEquals(sig.contentToString(), sigActual.contentToString())
        }
        val resultActual = Schnorr.verify(msg, pubKey, sig)
        assertEquals(result, resultActual)
    }

    @Test
    fun `test schnorr`() {
        val sk = "2b57492dbaee26c6cffe1da35efc77f8671984fc1b9d7a90bde354281fc5401b".toHexBytes()
        val msg = "e2ec89f5436bf49fe3f55daf71dbc8ce4af1de93cc597f9f947bf8b317ff06db".toHexBytes()
        val sig1 = Schnorr.sign(msg, sk)
        println(sig1.toHex())
        assertTrue(Schnorr.verify(msg, Point.genPubKey(sk), sig1))
        val sig2 = Schnorr.sign(msg, sk)
        assertNotEquals(sig1, sig2)
        assertTrue(Schnorr.verify(msg, Point.genPubKey(sk), sig2))
    }
}
