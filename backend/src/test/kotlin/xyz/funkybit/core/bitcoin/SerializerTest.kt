package xyz.funkybit.core.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bitcoinj.core.NetworkParameters
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.bitcoin.ArchAccountState
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

    @Test
    fun `test deserialize account state`() {
        val programStateBytes = "00000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a787937000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a78793700000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010101010101010101010101010101010101010101010101010101010101010102020202020202020202020202020202020202020202020202020202020202020303030303030303030303030303030303030303030303030303030303030303"
        val p = Borsh.decodeFromByteArray<ArchAccountState.Program>(programStateBytes.toHexBytes())
        assertEquals("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7", p.feeAccount.value)
        assertEquals("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7", p.programChangeAddress.value)
        assertEquals(BitcoinNetworkType.Mainnet, p.networkType)
        assertEquals(TxHash("0101010101010101010101010101010101010101010101010101010101010101"), p.settlementBatchHash)
        assertEquals(TxHash("0202020202020202020202020202020202020202020202020202020202020202"), p.lastSettlementBatchHash)
        assertEquals(TxHash("0303030303030303030303030303030303030303030303030303030303030303"), p.lastWithdrawalBatchHash)

        val tokenStateBytes = "000000000c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c746f6b656e31323300000000000000000000000000000000000000000000000003000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a7879370000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001027000000000000626372743171336e79756b6b706b6736796a317935746a366e6a3830646836376d3330703936336d7a787937000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000204e000000000000626372743171336e79756b6b706b6736796a327935746a366e6a3830646836376d3330703936336d7a7879370000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003075000000000000"
        val t = Borsh.decodeFromByteArray<ArchAccountState.Token>(tokenStateBytes.toHexBytes())
        assertEquals("token123", t.tokenId)
        assertEquals("0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c", t.programStateAccount.toHexString())
        assertEquals(
            listOf(
                ArchAccountState.Balance(BitcoinAddress.canonicalize("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7"), 10000.toULong()),
                ArchAccountState.Balance(BitcoinAddress.canonicalize("bcrt1q3nyukkpkg6yj1y5tj6nj80dh67m30p963mzxy7"), 20000.toULong()),
                ArchAccountState.Balance(BitcoinAddress.canonicalize("bcrt1q3nyukkpkg6yj2y5tj6nj80dh67m30p963mzxy7"), 30000.toULong()),
            ),
            t.balances,
        )
        t.balances.forEachIndexed { index, balance ->
            assertEquals(
                ArchAccountState.Token.getBalanceAtIndex(tokenStateBytes.toHexBytes(), index),
                t.balances[index],
            )
        }
    }
}
