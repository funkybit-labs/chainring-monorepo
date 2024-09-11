package xyz.funkybit.core.evm

import kotlinx.datetime.Clock
import org.bitcoinj.core.ECKey
import org.junit.jupiter.api.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.StructuredDataEncoder
import xyz.funkybit.apps.api.AuthorizeWalletAddressMessage
import xyz.funkybit.apps.api.middleware.SignInMessage
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.blockchain.checksumAddress
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import java.math.BigInteger
import kotlin.test.assertTrue

class EIP712HelperTest {

    private val privateKeyHex = "0x123456789"
    private val sender = EvmAddress("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val token = EvmAddress("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC")
    private val verifyingContract = EvmAddress("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB")
    private val chainId = ChainId(1337u)

    @Test
    fun `test eip signatures`() {
        val credentials = Credentials.create(privateKeyHex)

        val requests = listOf(
            EIP712Transaction.WithdrawTx(sender, TokenAddressAndChain(token, chainId), BigInteger("100000"), 1L, false, EvmSignature.emptySignature()),
            EIP712Transaction.WithdrawTx(sender, TokenAddressAndChain(EvmAddress.zero, chainId), BigInteger("100000"), 2L, false, EvmSignature.emptySignature()),
        )
        requests.forEach {
            val hash = EIP712Helper.computeHash(it, chainId, verifyingContract)
            val signature = ECHelper.signData(Credentials.create(privateKeyHex), hash)
            assertTrue(ECHelper.isValidSignature(hash, signature, credentials.checksumAddress()))
        }
    }

    @Test
    fun `test sign-in message serialization`() {
        val sampleSignInMessage = SignInMessage(
            "[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.",
            address = EvmAddress.generate().value,
            chainId = ChainId(1337U),
            timestamp = Clock.System.now().toString(),
        )

        val json = EIP712Helper.structuredDataAsJson(sampleSignInMessage)
        val parsedMessage = StructuredDataEncoder(json)

        // test reconstructed message hash matches original
        assertTrue { EIP712Helper.computeHash(sampleSignInMessage).contentEquals(parsedMessage.hashStructuredData()) }
    }

    @Test
    fun `test authorize message serialization`() {
        val bitcoinAddress = BitcoinAddress.fromKey(BitcoinClient.getParams(), ECKey())
        val message =
            "[funkybit] Please sign this message to authorize Bitcoin wallet ${bitcoinAddress.value}. This action will not cost any gas fees."

        val sampleAuthorizeMessage = AuthorizeWalletAddressMessage(
            message = message,
            authorizedAddress = bitcoinAddress.value,
            address = EvmAddress.generate().value,
            chainId = ChainId(1337U),
            timestamp = Clock.System.now().toString(),
        )

        val json = EIP712Helper.structuredDataAsJson(sampleAuthorizeMessage)
        val parsedMessage = StructuredDataEncoder(json)

        // test reconstructed message hash matches original
        assertTrue { EIP712Helper.computeHash(sampleAuthorizeMessage).contentEquals(parsedMessage.hashStructuredData()) }
    }
}
