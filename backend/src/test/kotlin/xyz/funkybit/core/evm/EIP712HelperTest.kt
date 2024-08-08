package xyz.funkybit.core.evm

import org.junit.jupiter.api.Test
import org.web3j.crypto.Credentials
import xyz.funkybit.core.blockchain.checksumAddress
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import java.math.BigInteger
import kotlin.test.assertTrue

class EIP712HelperTest {

    private val privateKeyHex = "0x123456789"
    private val sender = Address("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val token = Address("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC")
    private val verifyingContract = Address("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB")
    private val chainId = ChainId(1337u)

    @Test
    fun `test eip signatures`() {
        val credentials = Credentials.create(privateKeyHex)

        val requests = listOf(
            EIP712Transaction.WithdrawTx(sender, TokenAddressAndChain(token, chainId), BigInteger("100000"), 1L, false, EvmSignature.emptySignature()),
            EIP712Transaction.WithdrawTx(sender, TokenAddressAndChain(Address.zero, chainId), BigInteger("100000"), 2L, false, EvmSignature.emptySignature()),
        )
        requests.forEach {
            val hash = EIP712Helper.computeHash(it, chainId, verifyingContract)
            val signature = ECHelper.signData(Credentials.create(privateKeyHex), hash)
            assertTrue(ECHelper.isValidSignature(hash, signature, credentials.checksumAddress()))
        }
    }
}
