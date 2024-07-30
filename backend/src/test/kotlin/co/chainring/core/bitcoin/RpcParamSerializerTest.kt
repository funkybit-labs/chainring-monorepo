package co.chainring.core.bitcoin

import co.chainring.core.model.rpc.ArchNetworkRpc
import co.chainring.core.model.rpc.ArchRpcParams
import co.chainring.core.model.rpc.BitcoinRpcParams
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Test
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
