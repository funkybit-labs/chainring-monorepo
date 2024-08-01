package xyz.funkybit.core.bitcoin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.model.rpc.ArchRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
class RpcParamSerializerTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun test() {
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
}
