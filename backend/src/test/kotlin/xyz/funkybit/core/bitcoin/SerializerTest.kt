package xyz.funkybit.core.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.bitcoin.SerializedBitcoinTx
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.model.rpc.ArchRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
class SerializerTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `test rpc params`() {
        assertEquals(
            "\"hello\"",
            json.encodeToString(ArchRpcParams("hello")),
        )
        assertEquals(
            "[\"hello\",1,true,2]",
            json.encodeToString(BitcoinRpcParams(listOf("hello", 1, true, 2L))),
        )
        assertEquals(
            "{\"elf\":[18,52,86,240]}",
            json.encodeToString(ArchRpcParams(ArchNetworkRpc.DeployProgramParams(Hex.decode("123456F0").toUByteArray()))),
        )
    }

    @Test
    fun `test borsch`() {
        val exchangeInstruction: ProgramInstruction = ProgramInstruction.InitStateParams(
            BitcoinAddress.canonicalize("fee"),
            SerializedBitcoinTx("hello".toByteArray()),
        )

        assertEquals(
            Borsh.encodeToByteArray(exchangeInstruction).toHex(),
            Borsh.encodeToByteArray(Borsh.decodeFromByteArray<ProgramInstruction>(Borsh.encodeToByteArray(exchangeInstruction))).toHex(),
        )

        assertEquals(
            "00030000006665650500000068656c6c6f",
            Borsh.encodeToByteArray(exchangeInstruction).toHex(false),
        )

        assertEquals(
            "00030000006665650500000068656c6c6f",
            Borsh.encodeToByteArray(exchangeInstruction).toHex(false),
        )

        val programId = "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a550".toHexBytes()
        val instruction = ArchNetworkRpc.Instruction(
            programId = ArchNetworkRpc.Pubkey(programId.toUByteArray()),
            utxos = listOf(ArchNetworkRpc.UtxoMeta(TxHash("123"), 1)),
            data = Borsh.encodeToByteArray(exchangeInstruction).toUByteArray(),
        )

        assertEquals(
            Borsh.encodeToByteArray(instruction).toHex(),
            Borsh.encodeToByteArray(Borsh.decodeFromByteArray<ArchNetworkRpc.Instruction>(Borsh.encodeToByteArray(instruction))).toHex(),
        )

        assertEquals(
            "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a5500100000003000000313233010000001100000000030000006665650500000068656c6c6f",
            Borsh.encodeToByteArray(instruction).toHex(false),
        )
    }
}
