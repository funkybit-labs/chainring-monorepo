package xyz.funkybit.core.bitcoin

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.SegwitAddress
import org.junit.jupiter.api.Test
import xyz.funkybit.core.utils.bitcoin.TaprootUtils
import xyz.funkybit.core.utils.schnorr.Point
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import kotlin.test.assertEquals

class TaprootUtilsTest {

    @Test
    fun `test generate`() {
        val pubkey = "e45524da21d01c3c46d94cb54bdc98a2da45e2454370ca2cc0d47abb81048d8f".toHexBytes()
        assertEquals(
            "bcrt1pywlvpkpkkgwn2h2nm07gwv6djnfp2glm2u9xcgtyy4dutnekgnwqhxgztm",
            SegwitAddress.fromProgram(
                NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!,
                1,
                TaprootUtils.tweakPubkey(pubkey),
            ).toBech32(),
        )
    }

    @Test
    fun `test bip341 test vector 1`() {
        val pubkey = "d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d".toHexBytes()
        assertEquals(
            "bc1p2wsldez5mud2yam29q22wgfh9439spgduvct83k3pm50fcxa5dps59h4z5",
            SegwitAddress.fromProgram(
                NetworkParameters.fromID(NetworkParameters.ID_MAINNET)!!,
                1,
                TaprootUtils.tweakPubkey(pubkey),
            ).toBech32(),
        )
    }

    @Test
    fun `test bip341 test vector 2`() {
        val pubkey = "187791b6f712a8ea41c8ecdd0ee77fab3e85263b37e1ec18a3651926b3a6cf27".toHexBytes()
        assertEquals(
            "bc1pz37fc4cn9ah8anwm4xqqhvxygjf9rjf2resrw8h8w4tmvcs0863sa2e586",
            SegwitAddress.fromProgram(
                NetworkParameters.fromID(NetworkParameters.ID_MAINNET)!!,
                1,
                TaprootUtils.tweakPubkey(pubkey, "5b75adecf53548f3ec6ad7d78383bf84cc57b55a3127c72b9a2481752dd88b21".toHexBytes()),
            ).toBech32(),
        )
    }

    @Test
    fun `test tweak seckey`() {
        val secKey = "0c495259affa406cd5880b0da06fc0db1f0cab3293e380cfd8f333929f756ceb"
        val pubKey = Point.genPubKey(secKey.toHexBytes())
        val tweakedPubKey = TaprootUtils.tweakPubkey(pubKey)
        val tweakedSecKey = TaprootUtils.tweakSeckey(secKey.toHexBytes())
        assertEquals(
            tweakedPubKey.toHex(),
            Point.genPubKey(tweakedSecKey).toHex(),
        )
    }
}
