package xyz.funkybit.core.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bitcoinj.core.NetworkParameters
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.BitcoinNetworkType
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
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
    }

    @Test
    fun `test instructions`() {
        val exchangeInstruction: ProgramInstruction = ProgramInstruction.InitProgramStateParams(
            BitcoinAddress.canonicalize("fee"),
            BitcoinAddress.canonicalize("program"),
            BitcoinNetworkType.fromNetworkParams(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!),
        )

        assertEquals(
            Borsh.encodeToByteArray(exchangeInstruction).toHex(),
            Borsh.encodeToByteArray(Borsh.decodeFromByteArray<ProgramInstruction>(Borsh.encodeToByteArray(exchangeInstruction))).toHex(),
        )

        assertEquals(
            "00030000006665650700000070726f6772616d03",
            Borsh.encodeToByteArray(exchangeInstruction).toHex(false),
        )

        val programId = "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a550".toHexBytes()
        val instruction = ArchNetworkRpc.Instruction(
            programId = ArchNetworkRpc.Pubkey(programId.toUByteArray()),
            accounts = listOf(ArchNetworkRpc.AccountMeta(ArchNetworkRpc.Pubkey(programId.toUByteArray()), true, true)),
            data = Borsh.encodeToByteArray(exchangeInstruction).toUByteArray(),
        )

        assertEquals(
            "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a55001c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a5500101140000000000000000030000006665650700000070726f6772616d03",
            instruction.serialize().toHex(false),
        )
    }
}
