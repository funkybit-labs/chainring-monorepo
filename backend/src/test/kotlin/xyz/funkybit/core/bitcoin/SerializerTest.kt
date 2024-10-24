package xyz.funkybit.core.bitcoin

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
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolFormat
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            ExchangeProgramProtocolFormat.encodeToByteArray(exchangeInstruction).toHex(),
            ExchangeProgramProtocolFormat.encodeToByteArray(ExchangeProgramProtocolFormat.decodeFromByteArray<ProgramInstruction>(ExchangeProgramProtocolFormat.encodeToByteArray(exchangeInstruction))).toHex(),
        )

        assertEquals(
            "000300666565070070726f6772616d03",
            ExchangeProgramProtocolFormat.encodeToByteArray(exchangeInstruction).toHex(false),
        )

        val programId = "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a550".toHexBytes()
        val instruction = ArchNetworkRpc.Instruction(
            programId = ArchNetworkRpc.Pubkey(programId.toUByteArray()),
            accounts = listOf(ArchNetworkRpc.AccountMeta(ArchNetworkRpc.Pubkey(programId.toUByteArray()), true, true)),
            data = ExchangeProgramProtocolFormat.encodeToByteArray(exchangeInstruction).toUByteArray(),
        )

        assertEquals(
            "c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a55001c7ff63e7a3a9e801320d6ca0de8821ad927d8f65dc7f06458243341d4df8a55001011000000000000000000300666565070070726f6772616d03",
            instruction.serialize().toHex(false),
        )

        val walletAddress = BitcoinAddress.canonicalize("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7")

        val addressIndex = ProgramInstruction.AddressIndex(
            index = 100u,
            last4 = ProgramInstruction.WalletLast4.fromWalletAddress(walletAddress),
        )
        assertEquals(
            "640000007a787937",
            ExchangeProgramProtocolFormat.encodeToByteArray(addressIndex).toHex(false),
        )
        val deserialized = ExchangeProgramProtocolFormat.decodeFromByteArray<ProgramInstruction.AddressIndex>(ExchangeProgramProtocolFormat.encodeToByteArray(addressIndex))
        assertEquals(
            addressIndex.index,
            deserialized.index,
        )
        assertTrue(addressIndex.last4.value.contentEquals(deserialized.last4.value))
    }

    @Test
    fun `test deserialize account state`() {
        val programStateBytes = "00000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a787937000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a787937000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000101010101010101010101010101010101010101010101010101010101010101020202020202020202020202020202020202020202020202020202020202020203030303030303030303030303030303030303030303030303030303030303030000"
        val p = ExchangeProgramProtocolFormat.decodeFromByteArray<ArchAccountState.Program>(programStateBytes.toHexBytes())
        assertEquals("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7", p.feeAccount.value)
        assertEquals("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7", p.programChangeAddress.value)
        assertEquals(p.events, emptyList())
        assertEquals(BitcoinNetworkType.Mainnet, p.networkType)
        assertEquals(TxHash("0101010101010101010101010101010101010101010101010101010101010101"), p.settlementBatchHash)
        assertEquals(TxHash("0202020202020202020202020202020202020202020202020202020202020202"), p.lastSettlementBatchHash)
        assertEquals(TxHash("0303030303030303030303030303030303030303030303030303030303030303"), p.lastWithdrawalBatchHash)

        val tokenStateBytes = "000000000c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c746f6b656e31323300000000000000000000000000000000000000000000000003000000626372743171336e79756b6b706b6736796a307935746a366e6a3830646836376d3330703936336d7a7879370000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001027000000000000626372743171336e79756b6b706b6736796a317935746a366e6a3830646836376d3330703936336d7a787937000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000204e000000000000626372743171336e79756b6b706b6736796a327935746a366e6a3830646836376d3330703936336d7a7879370000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003075000000000000"
        val t = ExchangeProgramProtocolFormat.decodeFromByteArray<ArchAccountState.Token>(tokenStateBytes.toHexBytes())
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
        t.balances.forEachIndexed { index, _ ->
            assertEquals(
                ArchAccountState.Token.getBalanceAtIndex(tokenStateBytes.toHexBytes(), index),
                t.balances[index],
            )
        }
    }

    @Test
    fun `test deserialize processed instruction`() {
        val processedResponseBody = "{\"runtime_transaction\":{\"version\":0,\"signatures\":[[97,119,190,99,115,158,210,214,96,13,106,166,235,189,222,10,24,11,27,149,100,167,207,175,7,247,161,82,238,191,111,192,83,90,79,199,159,197,102,86,160,34,211,82,62,29,193,198,29,167,78,80,118,44,238,187,109,233,114,226,26,25,142,208]],\"message\":{\"signers\":[[69,248,215,233,211,100,178,204,102,73,157,195,242,167,94,198,44,86,224,53,235,58,37,178,168,67,154,85,71,219,172,76]],\"instructions\":[{\"program_id\":[109,214,1,223,124,113,28,251,225,109,220,211,103,120,77,32,237,139,249,75,152,160,124,130,56,12,150,84,238,255,175,149],\"accounts\":[{\"pubkey\":[69,248,215,233,211,100,178,204,102,73,157,195,242,167,94,198,44,86,224,53,235,58,37,178,168,67,154,85,71,219,172,76],\"is_signer\":true,\"is_writable\":true},{\"pubkey\":[40,197,5,148,102,73,7,22,208,33,91,72,8,109,146,79,169,89,21,152,30,142,202,44,148,202,24,168,170,103,235,81],\"is_signer\":false,\"is_writable\":true}],\"data\":[1,4,0,98,116,99,50]}]}},\"status\":\"Processed\",\"bitcoin_txid\":null,\"accounts_tags\":[]}"
        val failedResponseBody = "{\"runtime_transaction\":{\"version\":0,\"signatures\":[[97,119,190,99,115,158,210,214,96,13,106,166,235,189,222,10,24,11,27,149,100,167,207,175,7,247,161,82,238,191,111,192,83,90,79,199,159,197,102,86,160,34,211,82,62,29,193,198,29,167,78,80,118,44,238,187,109,233,114,226,26,25,142,208]],\"message\":{\"signers\":[[69,248,215,233,211,100,178,204,102,73,157,195,242,167,94,198,44,86,224,53,235,58,37,178,168,67,154,85,71,219,172,76]],\"instructions\":[{\"program_id\":[109,214,1,223,124,113,28,251,225,109,220,211,103,120,77,32,237,139,249,75,152,160,124,130,56,12,150,84,238,255,175,149],\"accounts\":[{\"pubkey\":[69,248,215,233,211,100,178,204,102,73,157,195,242,167,94,198,44,86,224,53,235,58,37,178,168,67,154,85,71,219,172,76],\"is_signer\":true,\"is_writable\":true},{\"pubkey\":[40,197,5,148,102,73,7,22,208,33,91,72,8,109,146,79,169,89,21,152,30,142,202,44,148,202,24,168,170,103,235,81],\"is_signer\":false,\"is_writable\":true}],\"data\":[1,4,0,98,116,99,50]}]}},\"status\":{\"Failed\":\"transaction error Error processing Instruction 0: program error: Custom program error: 0x261\"},\"bitcoin_txid\":null,\"accounts_tags\":[]}"
        val json = Json {
            ignoreUnknownKeys = true
        }
        assertEquals(
            ArchNetworkRpc.Status.Processed,
            json.decodeFromString<ArchNetworkRpc.ProcessedTransaction>(processedResponseBody).statusInfo.status,
        )

        val failedResponse = json.decodeFromString<ArchNetworkRpc.ProcessedTransaction>(failedResponseBody)
        assertEquals(
            ArchNetworkRpc.Status.Failed,
            failedResponse.statusInfo.status,
        )
        assertEquals(
            ArchNetworkRpc.ContractError.ALREADY_INITIALIZED,
            failedResponse.statusInfo.errorInfo?.code,
        )
    }
}
